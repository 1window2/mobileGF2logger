#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "zdtun.h"

#define LOG_TAG "GF2Capture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define PACKET_BUFFER_SIZE 65535
#define VPN_MTU 1500
#define SELECT_TIMEOUT_USEC 250000
#define PURGE_INTERVAL_SECONDS 1

typedef struct flow_state {
    int64_t id;
    bool inspect_payload;
} flow_state_t;

typedef struct capture_context {
    JavaVM *vm;
    JNIEnv *env;
    jobject vpn_service;
    jobject listener;
    jmethodID protect_method;
    jmethodID payload_method;
    jmethodID flow_closed_method;
    jmethodID traffic_method;
    pthread_t thread;
    atomic_bool running;
    int tun_fd;
    int64_t next_flow_id;
    uint64_t sent_bytes;
    uint64_t received_bytes;
    uint64_t inspected_bytes;
} capture_context_t;

static pthread_mutex_t context_mutex = PTHREAD_MUTEX_INITIALIZER;
static capture_context_t *active_context = NULL;

static bool clear_java_exception(capture_context_t *context, const char *operation) {
    if (!(*context->env)->ExceptionCheck(context->env)) {
        return false;
    }

    LOGE("Java callback failed during %s", operation);
    (*context->env)->ExceptionClear(context->env);
    return true;
}

static flow_state_t *get_flow_state(const zdtun_conn_t *connection) {
    return (flow_state_t *) zdtun_conn_get_userdata(connection);
}

static bool starts_with(const char *payload, uint16_t length, const char *prefix) {
    size_t prefix_length = strlen(prefix);
    return length >= prefix_length && memcmp(payload, prefix, prefix_length) == 0;
}

static bool is_web_transport(const char *payload, uint16_t length) {
    if (length >= 3) {
        uint8_t record_type = (uint8_t) payload[0];
        if (record_type >= 0x14U && record_type <= 0x17U &&
                (uint8_t) payload[1] == 0x03U) {
            return true;
        }
    }

    return starts_with(payload, length, "GET ") ||
            starts_with(payload, length, "POST ") ||
            starts_with(payload, length, "HEAD ") ||
            starts_with(payload, length, "PUT ") ||
            starts_with(payload, length, "HTTP/") ||
            starts_with(payload, length, "PRI * HTTP/2.0");
}

static void notify_payload(
        capture_context_t *context,
        const zdtun_conn_t *connection,
        bool is_sent,
        const char *payload,
        uint16_t payload_length) {
    if (payload_length == 0 || payload == NULL || context->env == NULL) {
        return;
    }

    flow_state_t *flow = get_flow_state(connection);
    if (flow == NULL || !flow->inspect_payload) {
        return;
    }

    if (is_web_transport(payload, payload_length)) {
        flow->inspect_payload = false;
        return;
    }

    if (is_sent) {
        return;
    }

    context->inspected_bytes += payload_length;

    jbyteArray bytes = (*context->env)->NewByteArray(context->env, payload_length);
    if (bytes == NULL) {
        clear_java_exception(context, "payload allocation");
        return;
    }

    (*context->env)->SetByteArrayRegion(
            context->env,
            bytes,
            0,
            payload_length,
            (const jbyte *) payload);
    if (!clear_java_exception(context, "payload copy")) {
        (*context->env)->CallVoidMethod(
                context->env,
                context->listener,
                context->payload_method,
                (jlong) flow->id,
                is_sent ? JNI_TRUE : JNI_FALSE,
                bytes);
        clear_java_exception(context, "payload delivery");
    }

    (*context->env)->DeleteLocalRef(context->env, bytes);
}

static int send_client_callback(
        zdtun_t *tunnel,
        zdtun_pkt_t *packet,
        const zdtun_conn_t *connection) {
    capture_context_t *context = (capture_context_t *) zdtun_userdata(tunnel);
    if (!atomic_load(&context->running)) {
        return 0;
    }

    context->received_bytes += packet->l7_len;
    notify_payload(context, connection, false, packet->l7, packet->l7_len);

    ssize_t written;
    do {
        written = write(context->tun_fd, packet->buf, packet->len);
    } while (written < 0 && errno == EINTR);

    if (written != (ssize_t) packet->len) {
        if (written < 0 && (errno == EIO || errno == EBADF)) {
            atomic_store(&context->running, false);
        } else {
            LOGE("TUN write failed: expected=%u actual=%zd errno=%d", packet->len, written, errno);
        }
        return -1;
    }

    return 0;
}

static void protect_socket_callback(zdtun_t *tunnel, socket_t socket_fd) {
    capture_context_t *context = (capture_context_t *) zdtun_userdata(tunnel);
    jboolean protected = (*context->env)->CallBooleanMethod(
            context->env,
            context->vpn_service,
            context->protect_method,
            (jint) socket_fd);

    if (clear_java_exception(context, "socket protection") || protected != JNI_TRUE) {
        LOGE("VpnService.protect failed for socket %d", socket_fd);
    }
}

static int connection_open_callback(zdtun_t *tunnel, zdtun_conn_t *connection) {
    capture_context_t *context = (capture_context_t *) zdtun_userdata(tunnel);
    flow_state_t *flow = calloc(1, sizeof(flow_state_t));
    if (flow == NULL) {
        LOGE("Unable to allocate flow state");
        return 1;
    }

    flow->id = ++context->next_flow_id;
    flow->inspect_payload = zdtun_conn_get_5tuple(connection)->ipproto == IPPROTO_TCP;
    zdtun_conn_set_userdata(connection, flow);
    return 0;
}

static void connection_close_callback(zdtun_t *tunnel, const zdtun_conn_t *connection) {
    capture_context_t *context = (capture_context_t *) zdtun_userdata(tunnel);
    flow_state_t *flow = get_flow_state(connection);
    if (flow == NULL) {
        return;
    }

    if (context->env != NULL) {
        (*context->env)->CallVoidMethod(
                context->env,
                context->listener,
                context->flow_closed_method,
                (jlong) flow->id);
        clear_java_exception(context, "flow closure");
    }

    free(flow);
}

static void process_outgoing_packet(
        capture_context_t *context,
        zdtun_t *tunnel,
        char *buffer,
        int packet_length) {
    zdtun_pkt_t packet;
    if (zdtun_parse_pkt(tunnel, buffer, (uint16_t) packet_length, &packet) != 0) {
        return;
    }

    if ((packet.flags & ZDTUN_PKT_IS_FRAGMENT) != 0) {
        return;
    }

    uint8_t tcp_flags = packet.tuple.ipproto == IPPROTO_TCP && packet.l4_hdr_len >= 14
            ? ((const uint8_t *) packet.l4)[13]
            : 0;
    bool established_tcp = packet.tuple.ipproto == IPPROTO_TCP &&
            ((tcp_flags & 0x02U) == 0 || (tcp_flags & 0x10U) != 0);
    zdtun_conn_t *connection = zdtun_lookup(tunnel, &packet.tuple, established_tcp ? 0 : 1);
    if (connection == NULL) {
        return;
    }

    notify_payload(context, connection, true, packet.l7, packet.l7_len);
    context->sent_bytes += packet.l7_len;

    if (zdtun_forward(tunnel, &packet, connection) != 0) {
        zdtun_conn_close(tunnel, connection, CONN_STATUS_ERROR);
    }
}

static void notify_traffic(capture_context_t *context) {
    (*context->env)->CallVoidMethod(
            context->env,
            context->listener,
            context->traffic_method,
            (jlong) context->sent_bytes,
            (jlong) context->received_bytes,
            (jlong) context->inspected_bytes);
    clear_java_exception(context, "traffic update");
}

static void release_context(capture_context_t *context) {
    if (context->env != NULL) {
        if (context->listener != NULL) {
            (*context->env)->DeleteGlobalRef(context->env, context->listener);
        }
        if (context->vpn_service != NULL) {
            (*context->env)->DeleteGlobalRef(context->env, context->vpn_service);
        }
    }

    if (context->tun_fd >= 0) {
        close(context->tun_fd);
    }
}

static void *capture_thread_main(void *argument) {
    capture_context_t *context = (capture_context_t *) argument;
    if ((*context->vm)->AttachCurrentThread(context->vm, &context->env, NULL) != JNI_OK) {
        LOGE("Unable to attach capture thread to JVM");
        atomic_store(&context->running, false);
        goto cleanup;
    }

    zdtun_callbacks_t callbacks = {
            .send_client = send_client_callback,
            .on_socket_open = protect_socket_callback,
            .on_connection_open = connection_open_callback,
            .on_connection_close = connection_close_callback,
    };
    zdtun_t *tunnel = zdtun_init(&callbacks, context);
    if (tunnel == NULL) {
        LOGE("zdtun_init failed");
        atomic_store(&context->running, false);
        goto detach;
    }

    zdtun_set_mtu(tunnel, VPN_MTU);
    signal(SIGPIPE, SIG_IGN);

    int descriptor_flags = fcntl(context->tun_fd, F_GETFL, 0);
    if (descriptor_flags >= 0) {
        fcntl(context->tun_fd, F_SETFL, descriptor_flags & ~O_NONBLOCK);
    }

    char *packet_buffer = malloc(PACKET_BUFFER_SIZE);
    if (packet_buffer == NULL) {
        LOGE("Unable to allocate packet buffer");
        atomic_store(&context->running, false);
        zdtun_finalize(tunnel);
        goto detach;
    }

    time_t last_purge = time(NULL);
    LOGI("Native packet loop started");

    while (atomic_load(&context->running)) {
        fd_set read_fds;
        fd_set write_fds;
        int max_fd = 0;
        zdtun_fds(tunnel, &max_fd, &read_fds, &write_fds);
        FD_SET(context->tun_fd, &read_fds);
        if (context->tun_fd > max_fd) {
            max_fd = context->tun_fd;
        }

        struct timeval timeout = {
                .tv_sec = 0,
                .tv_usec = SELECT_TIMEOUT_USEC,
        };
        int selected = select(max_fd + 1, &read_fds, &write_fds, NULL, &timeout);
        if (selected < 0) {
            if (errno == EINTR) {
                continue;
            }
            LOGE("select failed: errno=%d", errno);
            break;
        }

        if (!atomic_load(&context->running)) {
            break;
        }

        if (selected > 0 && FD_ISSET(context->tun_fd, &read_fds)) {
            ssize_t packet_length;
            do {
                packet_length = read(context->tun_fd, packet_buffer, PACKET_BUFFER_SIZE);
            } while (packet_length < 0 && errno == EINTR);

            if (packet_length > 0) {
                process_outgoing_packet(context, tunnel, packet_buffer, (int) packet_length);
            } else if (packet_length < 0 && errno != EAGAIN) {
                if (errno != EIO && errno != EBADF) {
                    LOGE("TUN read failed: errno=%d", errno);
                }
                break;
            }
        }

        if (selected > 0) {
            zdtun_handle_fd(tunnel, &read_fds, &write_fds);
        }

        time_t now = time(NULL);
        if (now - last_purge >= PURGE_INTERVAL_SECONDS) {
            zdtun_purge_expired(tunnel);
            notify_traffic(context);
            last_purge = now;
        }
    }

    atomic_store(&context->running, false);
    free(packet_buffer);
    zdtun_finalize(tunnel);
    LOGI("Native packet loop stopped");

detach:
    (*context->vm)->DetachCurrentThread(context->vm);
    context->env = NULL;

cleanup:
    release_context(context);
    pthread_mutex_lock(&context_mutex);
    if (active_context == context) {
        active_context = NULL;
    }
    pthread_mutex_unlock(&context_mutex);
    free(context);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_dev_gf2log_app_capture_NativeCaptureBridge_nativeStart(
        JNIEnv *env,
        jobject bridge,
        jint tun_file_descriptor,
        jobject vpn_service,
        jobject listener) {
    (void) bridge;

    pthread_mutex_lock(&context_mutex);
    if (active_context != NULL) {
        pthread_mutex_unlock(&context_mutex);
        return JNI_FALSE;
    }

    capture_context_t *context = calloc(1, sizeof(capture_context_t));
    if (context == NULL) {
        pthread_mutex_unlock(&context_mutex);
        return JNI_FALSE;
    }

    context->tun_fd = -1;
    if ((*env)->GetJavaVM(env, &context->vm) != JNI_OK) {
        free(context);
        pthread_mutex_unlock(&context_mutex);
        return JNI_FALSE;
    }

    context->tun_fd = dup(tun_file_descriptor);
    context->vpn_service = (*env)->NewGlobalRef(env, vpn_service);
    context->listener = (*env)->NewGlobalRef(env, listener);
    if (context->tun_fd < 0 || context->vpn_service == NULL || context->listener == NULL) {
        context->env = env;
        release_context(context);
        free(context);
        pthread_mutex_unlock(&context_mutex);
        return JNI_FALSE;
    }

    jclass service_class = (*env)->GetObjectClass(env, vpn_service);
    jclass listener_class = (*env)->GetObjectClass(env, listener);
    context->protect_method = (*env)->GetMethodID(env, service_class, "protect", "(I)Z");
    context->payload_method = (*env)->GetMethodID(env, listener_class, "onPayload", "(JZ[B)V");
    context->flow_closed_method = (*env)->GetMethodID(env, listener_class, "onFlowClosed", "(J)V");
    context->traffic_method = (*env)->GetMethodID(env, listener_class, "onTraffic", "(JJJ)V");
    (*env)->DeleteLocalRef(env, service_class);
    (*env)->DeleteLocalRef(env, listener_class);

    if (context->protect_method == NULL || context->payload_method == NULL ||
            context->flow_closed_method == NULL || context->traffic_method == NULL ||
            (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        context->env = env;
        release_context(context);
        free(context);
        pthread_mutex_unlock(&context_mutex);
        return JNI_FALSE;
    }

    atomic_init(&context->running, true);
    active_context = context;
    if (pthread_create(&context->thread, NULL, capture_thread_main, context) != 0) {
        active_context = NULL;
        context->env = env;
        release_context(context);
        free(context);
        pthread_mutex_unlock(&context_mutex);
        return JNI_FALSE;
    }

    pthread_mutex_unlock(&context_mutex);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_gf2log_app_capture_NativeCaptureBridge_nativeStop(JNIEnv *env, jobject bridge) {
    (void) env;
    (void) bridge;

    pthread_mutex_lock(&context_mutex);
    capture_context_t *context = active_context;
    if (context == NULL) {
        pthread_mutex_unlock(&context_mutex);
        return;
    }

    atomic_store(&context->running, false);
    pthread_t thread = context->thread;
    pthread_mutex_unlock(&context_mutex);

    if (!pthread_equal(pthread_self(), thread)) {
        pthread_join(thread, NULL);
    }
}
