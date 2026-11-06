/*
 * Host regression tests for the bundled zdtun forwarding core.
 *
 * zdtun.c is compiled into this file so the tests can reach its private connection state, and
 * every socket call it makes is redirected to an in-memory fake: no network, root or device is
 * needed. Build and run with app/src/test/cpp/run-zdtun-host-test.sh on Linux or macOS.
 */

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#if !defined(_LITTLE_ENDIAN) && (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
#define _LITTLE_ENDIAN 1
#endif

#include "zdtun.h"

#ifndef MSG_MORE
#define MSG_MORE 0
#endif
#ifndef CLOCK_MONOTONIC_COARSE
#define CLOCK_MONOTONIC_COARSE CLOCK_MONOTONIC
#endif
#if !defined(IPV6_ADD_MEMBERSHIP) && defined(IPV6_JOIN_GROUP)
#define IPV6_ADD_MEMBERSHIP IPV6_JOIN_GROUP
#endif

/* ******************************************************* */
/* Fake sockets: real descriptors (dups of /dev/null) so FD_SET and close work, fake behaviour. */

typedef struct {
  bool shut_wr;
  size_t sent_at_shutdown;
  bool send_blocked; // send fails with EAGAIN
  char sent[4096];
  size_t sent_len;
  char rx[512];
  size_t rx_len;
  bool rx_eof;
} fake_socket_t;

static fake_socket_t fakes[FD_SETSIZE];
static int devnull_fd = -1;

static int stub_socket(int domain, int type, int protocol) {
  (void) domain; (void) type; (void) protocol;
  int fd = dup(devnull_fd);

  if((fd >= 0) && (fd < FD_SETSIZE))
    memset(&fakes[fd], 0, sizeof(fakes[fd]));
  return fd;
}

static int stub_connect(int fd, const void *addr, socklen_t len) {
  (void) fd; (void) addr; (void) len;
  return 0;
}

static ssize_t stub_send(int fd, const void *buf, size_t len, int flags) {
  fake_socket_t *fake = &fakes[fd];
  (void) flags;

  if(fake->shut_wr) {
    errno = EPIPE;
    return -1;
  }
  if(fake->send_blocked) {
    errno = EAGAIN;
    return -1;
  }
  if(fake->sent_len + len > sizeof(fake->sent)) {
    errno = ENOBUFS;
    return -1;
  }

  memcpy(fake->sent + fake->sent_len, buf, len);
  fake->sent_len += len;
  return (ssize_t) len;
}

static ssize_t stub_sendto(int fd, const void *buf, size_t len, int flags,
        const void *addr, socklen_t addrlen) {
  (void) fd; (void) buf; (void) flags; (void) addr; (void) addrlen;
  return (ssize_t) len;
}

static ssize_t stub_recv(int fd, void *buf, size_t len, int flags) {
  fake_socket_t *fake = &fakes[fd];
  (void) flags;

  if(fake->rx_len > 0) {
    size_t n = (fake->rx_len < len) ? fake->rx_len : len;
    memcpy(buf, fake->rx, n);
    fake->rx_len = 0;
    return (ssize_t) n;
  }
  if(fake->rx_eof)
    return 0;

  errno = EAGAIN;
  return -1;
}

static int stub_shutdown(int fd, int how) {
  (void) how;
  fakes[fd].shut_wr = true;
  fakes[fd].sent_at_shutdown = fakes[fd].sent_len;
  return 0;
}

static int stub_setsockopt(int fd, int level, int name, const void *val, socklen_t len) {
  (void) fd; (void) level; (void) name; (void) val; (void) len;
  return 0;
}

static int stub_getsockopt(int fd, int level, int name, void *val, socklen_t *len) {
  (void) fd; (void) level; (void) name;
  memset(val, 0, *len);
  return 0;
}

static int stub_bind(int fd, const void *addr, socklen_t len) {
  (void) fd; (void) addr; (void) len;
  return 0;
}

static int stub_getsockname(int fd, void *addr, socklen_t *len) {
  struct sockaddr_in *addr4 = (struct sockaddr_in *) addr;
  (void) len;
  addr4->sin_port = htons((uint16_t) (40000 + fd));
  return 0;
}

static int stub_ioctl(int fd, unsigned long request, void *arg) {
  (void) fd; (void) request;
  *(int *) arg = 0;
  return 0;
}

#define socket(d, t, p) stub_socket(d, t, p)
#define connect(s, a, l) stub_connect(s, a, l)
#define send(s, b, l, f) stub_send(s, b, l, f)
#define sendto(s, b, l, f, a, al) stub_sendto(s, b, l, f, a, al)
#define recv(s, b, l, f) stub_recv(s, b, l, f)
#define shutdown(s, h) stub_shutdown(s, h)
#define setsockopt(s, l, n, v, vl) stub_setsockopt(s, l, n, v, vl)
#define getsockopt(s, l, n, v, vl) stub_getsockopt(s, l, n, v, vl)
#define bind(s, a, l) stub_bind(s, a, l)
#define getsockname(s, a, l) stub_getsockname(s, a, l)
#define ioctl(s, r, a) stub_ioctl(s, r, a)

#include "zdtun.c"

#undef socket
#undef connect
#undef send
#undef sendto
#undef recv
#undef shutdown
#undef setsockopt
#undef getsockopt
#undef bind
#undef getsockname
#undef ioctl

// utils.c needs Linux TUN headers; only its checksum is used by zdtun.c.
u_int16_t calc_checksum(u_int16_t start, const u_int8_t *buffer, u_int16_t length) {
  uint32_t sum = start;

  for(u_int16_t i = 0; i + 1 < length; i += 2)
    sum += (uint32_t) ((buffer[i] << 8) | buffer[i + 1]);
  if(length & 1)
    sum += (uint32_t) (buffer[length - 1] << 8);
  while(sum >> 16)
    sum = (sum & 0xFFFF) + (sum >> 16);

  return (u_int16_t) sum;
}

/* ******************************************************* */
/* Test harness */

static int failures = 0;
static const char *current_test = "";

#define CHECK(cond) do { \
  if(!(cond)) { \
    fprintf(stderr, "FAIL %s (%s:%d): %s\n", current_test, __FILE__, __LINE__, #cond); \
    failures++; \
  } \
} while(0)

typedef struct {
  bool keep;
} test_flow_t;

typedef struct {
  uint16_t client_port; // host order
  uint8_t flags;
  uint32_t seq;
  uint32_t ack;
  uint16_t payload_len;
} reply_t;

static reply_t replies[512];
static int num_replies;
static int num_opens;
static int num_closes;
static int live_flows;

static void reset_counters(void) {
  num_replies = 0;
  num_opens = 0;
  num_closes = 0;
  live_flows = 0;
}

static int on_send_client(zdtun_t *tun, zdtun_pkt_t *pkt, const zdtun_conn_t *conn) {
  (void) tun; (void) conn;

  if((pkt->tuple.ipproto == IPPROTO_TCP) && (num_replies < (int) (sizeof(replies) / sizeof(replies[0])))) {
    reply_t *reply = &replies[num_replies++];

    reply->client_port = ntohs(pkt->tcp->th_dport);
    reply->flags = pkt->tcp->th_flags;
    reply->seq = ntohl(pkt->tcp->th_seq);
    reply->ack = ntohl(pkt->tcp->th_ack);
    reply->payload_len = pkt->l7_len;
  }
  return 0;
}

// Like gf2capture: only TCP flows carry state, and all of them start out inspected.
static int on_open(zdtun_t *tun, zdtun_conn_t *conn) {
  (void) tun;
  num_opens++;

  if(zdtun_conn_get_5tuple(conn)->ipproto == IPPROTO_TCP) {
    test_flow_t *flow = calloc(1, sizeof(test_flow_t));
    flow->keep = true;
    zdtun_conn_set_userdata(conn, flow);
    live_flows++;
  }
  return 0;
}

static void on_close(zdtun_t *tun, const zdtun_conn_t *conn) {
  (void) tun;
  num_closes++;

  test_flow_t *flow = zdtun_conn_get_userdata(conn);
  if(flow) {
    zdtun_conn_set_userdata((zdtun_conn_t *) conn, NULL);
    free(flow);
    live_flows--;
  }
}

static int on_purge_priority(zdtun_t *tun, const zdtun_conn_t *conn) {
  (void) tun;
  const test_flow_t *flow = zdtun_conn_get_userdata(conn);
  return (flow && flow->keep) ? 1 : 0;
}

static zdtun_t *new_tunnel(void) {
  zdtun_callbacks_t callbacks = {
    .send_client = on_send_client,
    .on_connection_open = on_open,
    .on_connection_close = on_close,
    .on_purge_priority = on_purge_priority,
  };

  reset_counters();
  return zdtun_init(&callbacks, NULL);
}

/* ******************************************************* */
/* Client packets */

#define CLIENT_IP htonl(0x0A000002) // 10.0.0.2
#define SERVER_IP htonl(0x08080808) // 8.8.8.8

static char pkt_buf[2048];

static int build_ipv4(uint8_t proto, uint16_t l4_len) {
  struct iphdr *ip = (struct iphdr *) pkt_buf;

  memset(pkt_buf, 0, sizeof(pkt_buf));
  ip->version = 4;
  ip->ihl = 5;
  ip->tot_len = htons(IPV4_HEADER_LEN + l4_len);
  ip->frag_off = htons(0x4000);
  ip->ttl = 64;
  ip->protocol = proto;
  ip->saddr = CLIENT_IP;
  ip->daddr = SERVER_IP;
  return IPV4_HEADER_LEN + l4_len;
}

static int build_tcp(uint16_t sport, uint8_t flags, uint32_t seq, const char *payload, uint16_t len) {
  int total = build_ipv4(IPPROTO_TCP, TCP_HEADER_LEN + len);
  struct tcphdr *tcp = (struct tcphdr *) (pkt_buf + IPV4_HEADER_LEN);

  tcp->th_sport = htons(sport);
  tcp->th_dport = htons(443);
  tcp->th_seq = htonl(seq);
  tcp->th_ack = htonl(0x77EB77EC);
  tcp->th_off = 5;
  tcp->th_flags = flags;
  tcp->th_win = htons(65535);
  if(len > 0)
    memcpy(pkt_buf + IPV4_HEADER_LEN + TCP_HEADER_LEN, payload, len);
  return total;
}

static int build_udp(uint16_t sport, uint16_t dport, const uint8_t *payload, uint16_t len) {
  int total = build_ipv4(IPPROTO_UDP, UDP_HEADER_LEN + len);
  struct udphdr *udp = (struct udphdr *) (pkt_buf + IPV4_HEADER_LEN);

  udp->uh_sport = htons(sport);
  udp->uh_dport = htons(dport);
  udp->uh_ulen = htons(UDP_HEADER_LEN + len);
  memcpy(pkt_buf + IPV4_HEADER_LEN + UDP_HEADER_LEN, payload, len);
  return total;
}

static int build_icmp_echo(uint16_t id) {
  int total = build_ipv4(IPPROTO_ICMP, sizeof(struct icmphdr));
  struct icmphdr *icmp = (struct icmphdr *) (pkt_buf + IPV4_HEADER_LEN);

  icmp->type = ICMP_ECHO;
  icmp->un.echo.id = htons(id);
  icmp->un.echo.sequence = htons(1);
  return total;
}

// Mirrors gf2capture.c process_outgoing_packet. Returns the looked-up connection, even if closed.
static zdtun_conn_t *client_send(zdtun_t *tun, int len) {
  zdtun_pkt_t pkt;

  if(zdtun_parse_pkt(tun, pkt_buf, (uint16_t) len, &pkt) != 0)
    return NULL;

  bool established = (pkt.tuple.ipproto == IPPROTO_TCP) &&
      (!(pkt.tcp->th_flags & TH_SYN) || (pkt.tcp->th_flags & TH_ACK));
  zdtun_conn_t *conn = zdtun_lookup(tun, &pkt.tuple, established ? 0 : 1);

  if(conn && (zdtun_forward(tun, &pkt, conn) != 0))
    zdtun_conn_close(tun, conn, CONN_STATUS_ERROR);
  return conn;
}

static zdtun_conn_t *open_tcp(zdtun_t *tun, uint16_t sport, uint32_t isn) {
  return client_send(tun, build_tcp(sport, TH_SYN, isn, NULL, 0));
}

static zdtun_conn_t *open_udp(zdtun_t *tun, uint16_t sport) {
  const uint8_t payload[] = {'x'};
  return client_send(tun, build_udp(sport, 9999, payload, sizeof(payload)));
}

static void handle_fd(zdtun_t *tun, const zdtun_conn_t *conn, bool readable, bool writable) {
  fd_set rd, wr;

  FD_ZERO(&rd);
  FD_ZERO(&wr);
  if(readable)
    FD_SET(conn->sock, &rd);
  if(writable)
    FD_SET(conn->sock, &wr);
  zdtun_handle_fd(tun, &rd, &wr);
}

static int count_replies(uint16_t client_port, uint8_t flag) {
  int count = 0;

  for(int i = 0; i < num_replies; i++) {
    if((replies[i].client_port == client_port) && (replies[i].flags & flag))
      count++;
  }
  return count;
}

static const reply_t *last_reply(void) {
  return (num_replies > 0) ? &replies[num_replies - 1] : NULL;
}

static bool is_live(zdtun_t *tun, const zdtun_conn_t *conn) {
  zdtun_conn_t *found = NULL;

  HASH_FIND(hh, tun->conn_table, &conn->tuple, sizeof(conn->tuple), found);
  return (found == conn) && (found->status < CONN_STATUS_CLOSED);
}

/* ******************************************************* */
/* sec-native-forward-path-1: a refused socket still closes the flow exactly once */

static void test_tcp_socket_refusal_closes_flow_and_resets_client(void) {
  current_test = __func__;
  zdtun_t *tun = new_tunnel();

  tun->stats.num_open_sockets = MAX_NUM_SOCKETS; // nothing to purge, so the cap refuses the socket
  zdtun_conn_t *conn = open_tcp(tun, 40001, 1000);

  CHECK(conn != NULL);
  CHECK(num_opens == 1);
  CHECK(num_closes == 1);
  CHECK(live_flows == 0);
  CHECK(conn && (conn->status == CONN_STATUS_SOCKET_ERROR));
  CHECK(num_replies == 1);
  CHECK(last_reply() && (last_reply()->flags == (TH_RST | TH_ACK)));
  CHECK(last_reply() && (last_reply()->ack == 1001)); // acknowledges the SYN, so SYN-SENT accepts it

  // A retransmitted SYN gets a fresh flow, which is again opened and closed once.
  open_tcp(tun, 40001, 1000);
  CHECK(num_opens == 2);
  CHECK(num_closes == 2);
  CHECK(live_flows == 0);
  CHECK(count_replies(40001, TH_RST) == 2);

  tun->stats.num_open_sockets = 0;
  zdtun_finalize(tun);
  CHECK(num_closes == 2);
}

static void test_icmp_socket_refusal_closes_flow(void) {
  current_test = __func__;
  zdtun_t *tun = new_tunnel();

  tun->stats.num_open_sockets = MAX_NUM_SOCKETS;
  zdtun_conn_t *conn = client_send(tun, build_icmp_echo(7));

  CHECK(conn != NULL);
  CHECK(num_opens == 1);
  CHECK(num_closes == 1);
  CHECK(conn && (conn->status == CONN_STATUS_SOCKET_ERROR));

  tun->stats.num_open_sockets = 0;
  zdtun_finalize(tun);
  CHECK(num_closes == 1);
}

/* ******************************************************* */
/* sec-native-forward-path-2: the forced purge spares the flows that may carry game frames */

static void test_forced_purge_takes_datagrams_before_tcp(void) {
  current_test = __func__;
  zdtun_t *tun = new_tunnel();

  zdtun_conn_t *inspected = open_tcp(tun, 41000, 1);
  zdtun_conn_t *plain = open_tcp(tun, 41001, 1);
  CHECK(inspected && (inspected->status == CONN_STATUS_CONNECTED));
  CHECK(plain && (plain->status == CONN_STATUS_CONNECTED));
  ((test_flow_t *) plain->user_data)->keep = false;

  // Both TCP flows are the oldest connections, which the old timestamp-only purge took first.
  inspected->tstamp -= 30;
  plain->tstamp -= 20;

  for(uint16_t port = 0; tun->stats.num_open_sockets < MAX_NUM_SOCKETS; port++)
    open_udp(tun, (uint16_t) (50000 + port));
  CHECK(tun->stats.num_open_sockets == MAX_NUM_SOCKETS);

  num_replies = 0;
  zdtun_conn_t *fresh = open_tcp(tun, 41002, 1); // at the cap, so the lookup forces a purge

  CHECK(is_live(tun, inspected));
  CHECK(is_live(tun, plain));
  CHECK(fresh && is_live(tun, fresh));
  CHECK(count_replies(41000, TH_RST) == 0);
  CHECK(count_replies(41001, TH_RST) == 0);
  CHECK(tun->stats.num_open_sockets == NUM_SOCKETS_AFTER_PURGE + 1);
  CHECK(tun->stats.num_udp_conn == (MAX_NUM_SOCKETS - 2) - (MAX_NUM_SOCKETS - NUM_SOCKETS_AFTER_PURGE));

  zdtun_finalize(tun);
  CHECK(live_flows == 0);
}

static void test_forced_purge_takes_plain_tcp_before_inspected_tcp(void) {
  current_test = __func__;
  zdtun_t *tun = new_tunnel();

  zdtun_conn_t *inspected = open_tcp(tun, 42000, 1);
  CHECK(inspected != NULL);
  inspected->tstamp -= 30;

  for(uint16_t port = 1; tun->stats.num_open_sockets < MAX_NUM_SOCKETS; port++) {
    zdtun_conn_t *conn = open_tcp(tun, (uint16_t) (42000 + port), 1);
    ((test_flow_t *) conn->user_data)->keep = false;
  }

  num_replies = 0;
  zdtun_purge_expired(tun);

  CHECK(is_live(tun, inspected));
  CHECK(count_replies(42000, TH_RST) == 0);
  CHECK(tun->stats.num_open_sockets == NUM_SOCKETS_AFTER_PURGE);
  CHECK(num_replies == MAX_NUM_SOCKETS - NUM_SOCKETS_AFTER_PURGE); // one RST per purged plain flow

  zdtun_finalize(tun);
  CHECK(live_flows == 0);
}

static void test_forced_purge_does_not_count_socketless_connections(void) {
  current_test = __func__;
  zdtun_t *tun = new_tunnel();

  // The server closes first: the connection waits for the client FIN without a socket.
  zdtun_conn_t *half_closed = open_tcp(tun, 43000, 1);
  CHECK(half_closed != NULL);
  fakes[half_closed->sock].rx_eof = true;
  handle_fd(tun, half_closed, true, false);
  CHECK(half_closed->sock == INVALID_SOCKET);
  CHECK(half_closed->status == CONN_STATUS_CONNECTED);
  half_closed->tstamp -= 30;

  for(uint16_t port = 0; tun->stats.num_open_sockets < MAX_NUM_SOCKETS; port++)
    open_udp(tun, (uint16_t) (50000 + port));

  zdtun_purge_expired(tun);

  // Destroying the socketless connection freed no socket, so it must not count toward the purge.
  CHECK(tun->stats.num_open_sockets == NUM_SOCKETS_AFTER_PURGE);
  CHECK(live_flows == 0);

  zdtun_finalize(tun);
}

/* ******************************************************* */
/* sec-native-forward-path-3: the DNS QR bit is read in host order */

static void test_dns_flags_read_in_network_order(void) {
  current_test = __func__;
  zdtun_t *tun = new_tunnel();
  // transaction id, flags, 1 question, 0 answers, 0 authority, 0 additional, first label byte
  const uint8_t query[] = {0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0x00};
  const uint8_t response[] = {0x12, 0x34, 0x81, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0x00};
  const uint8_t recursive_query[] = {0x56, 0x78, 0x01, 0x80, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0x00};

  zdtun_conn_t *conn = client_send(tun, build_udp(45000, 53, query, sizeof(query)));
  CHECK(conn && (conn->dns.pending_queries == 1));

  // RA=0 response: QR alone must mark it as the answer and close the flow at once.
  memcpy(fakes[conn->sock].rx, response, sizeof(response));
  fakes[conn->sock].rx_len = sizeof(response);
  handle_fd(tun, conn, true, false);
  CHECK(conn->dns.pending_queries == 0);
  CHECK(conn->status >= CONN_STATUS_CLOSED);

  // RA=1 query: still a query.
  zdtun_conn_t *other = client_send(tun, build_udp(45001, 53, recursive_query, sizeof(recursive_query)));
  CHECK(other && (other->dns.pending_queries == 1));

  zdtun_finalize(tun);
}

/* ******************************************************* */
/* sec-native-forward-path-4: the client FIN follows every byte before it */

#define ISN 5000

static zdtun_conn_t *established_tcp(zdtun_t *tun, uint16_t sport) {
  zdtun_conn_t *conn = open_tcp(tun, sport, ISN);

  CHECK(conn && (conn->status == CONN_STATUS_CONNECTED));
  CHECK(conn && (conn->tcp.client_seq == ISN + 1));
  return conn;
}

static void test_fin_waits_for_queued_bytes(void) {
  current_test = __func__;
  zdtun_t *tun = new_tunnel();
  zdtun_conn_t *conn = established_tcp(tun, 46000);
  fake_socket_t *server = &fakes[conn->sock];

  server->send_blocked = true;
  client_send(tun, build_tcp(46000, TH_PUSH | TH_ACK, ISN + 1, "hello", 5));
  handle_fd(tun, conn, false, true);
  CHECK(conn->tcp.tx_queue_size == 5);

  num_replies = 0;
  client_send(tun, build_tcp(46000, TH_FIN | TH_ACK, ISN + 6, "abc", 3));
  CHECK(!server->shut_wr);
  CHECK(num_replies == 0); // neither the payload nor the FIN is ACKed before it is sent

  // A retransmission of the same FIN changes nothing.
  client_send(tun, build_tcp(46000, TH_FIN | TH_ACK, ISN + 6, "abc", 3));
  CHECK(conn->tcp.tx_queue_size == 8);
  CHECK(num_replies == 0);

  server->send_blocked = false;
  handle_fd(tun, conn, false, true);
  CHECK(server->sent_len == 8);
  CHECK(memcmp(server->sent, "helloabc", 8) == 0);
  CHECK(server->shut_wr);
  CHECK(server->sent_at_shutdown == 8);
  CHECK(last_reply() && (last_reply()->flags == TH_ACK));
  CHECK(last_reply() && (last_reply()->ack == ISN + 1 + 5 + 3 + 1));
  CHECK(count_replies(46000, TH_RST) == 0);

  // A FIN retransmitted after it was ACKed is ACKed again, not counted twice.
  client_send(tun, build_tcp(46000, TH_FIN | TH_ACK, ISN + 9, NULL, 0));
  CHECK(conn->tcp.client_seq == ISN + 1 + 5 + 3 + 1);
  CHECK(last_reply() && (last_reply()->ack == ISN + 1 + 5 + 3 + 1));
  CHECK(is_live(tun, conn));

  zdtun_finalize(tun);
}

static void test_fin_payload_is_delivered(void) {
  current_test = __func__;
  zdtun_t *tun = new_tunnel();
  zdtun_conn_t *conn = established_tcp(tun, 46001);
  fake_socket_t *server = &fakes[conn->sock];

  client_send(tun, build_tcp(46001, TH_FIN | TH_ACK, ISN + 1, "bye", 3));
  CHECK(!server->shut_wr);

  handle_fd(tun, conn, false, true);
  CHECK(server->sent_len == 3);
  CHECK(memcmp(server->sent, "bye", 3) == 0);
  CHECK(server->shut_wr && (server->sent_at_shutdown == 3));
  CHECK(last_reply() && (last_reply()->ack == ISN + 1 + 3 + 1));
  CHECK(count_replies(46001, TH_RST) == 0);

  zdtun_finalize(tun);
}

static void test_bare_fin_is_forwarded_at_once(void) {
  current_test = __func__;
  zdtun_t *tun = new_tunnel();
  zdtun_conn_t *conn = established_tcp(tun, 46002);
  fake_socket_t *server = &fakes[conn->sock];

  client_send(tun, build_tcp(46002, TH_FIN | TH_ACK, ISN + 1, NULL, 0));
  CHECK(server->shut_wr);
  CHECK(last_reply() && (last_reply()->flags == TH_ACK));
  CHECK(last_reply() && (last_reply()->ack == ISN + 2));

  zdtun_finalize(tun);
}

static void test_server_close_acks_pending_fin(void) {
  current_test = __func__;
  zdtun_t *tun = new_tunnel();
  zdtun_conn_t *conn = established_tcp(tun, 46003);
  fake_socket_t *server = &fakes[conn->sock];

  server->send_blocked = true;
  client_send(tun, build_tcp(46003, TH_PUSH | TH_ACK, ISN + 1, "hello", 5));
  client_send(tun, build_tcp(46003, TH_FIN | TH_ACK, ISN + 6, NULL, 0));
  CHECK(!server->shut_wr);

  num_replies = 0;
  server->rx_eof = true;
  handle_fd(tun, conn, true, false);

  // The server is gone: its FIN reaches the client, and the client's FIN is ACKed so its close
  // completes without an RST.
  CHECK(count_replies(46003, TH_FIN) == 1);
  CHECK(last_reply() && (last_reply()->flags == TH_ACK));
  CHECK(last_reply() && (last_reply()->ack == ISN + 1 + 5 + 1));
  CHECK(count_replies(46003, TH_RST) == 0);
  CHECK(conn->status >= CONN_STATUS_CLOSED);

  zdtun_finalize(tun);
}

/* ******************************************************* */

int main(void) {
  devnull_fd = open("/dev/null", O_RDWR);
  if(devnull_fd < 0) {
    perror("open /dev/null");
    return 2;
  }

  test_tcp_socket_refusal_closes_flow_and_resets_client();
  test_icmp_socket_refusal_closes_flow();
  test_forced_purge_takes_datagrams_before_tcp();
  test_forced_purge_takes_plain_tcp_before_inspected_tcp();
  test_forced_purge_does_not_count_socketless_connections();
  test_dns_flags_read_in_network_order();
  test_fin_waits_for_queued_bytes();
  test_fin_payload_is_delivered();
  test_bare_fin_is_forwarded_at_once();
  test_server_close_acks_pending_fin();

  if(failures) {
    fprintf(stderr, "%d zdtun host check(s) failed\n", failures);
    return 1;
  }

  printf("zdtun host tests passed\n");
  return 0;
}
