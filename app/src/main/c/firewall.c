#include <jni.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <time.h>
#include "zdtun.h"

typedef struct {
    JNIEnv *env;
    jobject session;
    int fd;
    jmethodID open_flow;
    jmethodID blocked;
    jmethodID record;
    jmethodID protect;
    jmethodID running;
    jmethodID revision;
} bridge;

static jbyteArray ip_bytes(JNIEnv *env, const zdtun_ip_t *ip, int version) {
    jsize length = version == 4 ? 4 : 16;
    jbyteArray address = (*env)->NewByteArray(env, length);
    if (address) (*env)->SetByteArrayRegion(env, address, 0, length, (const jbyte *)ip);
    return address;
}

static int blocked(bridge *state, jobject flow) {
    JNIEnv *env = state->env;
    return (*env)->ExceptionCheck(env) || !flow
        || (*env)->CallBooleanMethod(env, state->session, state->blocked, flow);
}

static void record(bridge *state, jobject flow, int size, int outbound, int denied) {
    JNIEnv *env = state->env;
    if (!(*env)->ExceptionCheck(env)) {
        (*env)->CallVoidMethod(env, state->session, state->record, flow, (jint)size,
                               (jboolean)outbound, (jboolean)denied);
    }
}

static int open_connection(zdtun_t *tun, zdtun_conn_t *conn) {
    bridge *state = zdtun_userdata(tun);
    JNIEnv *env = state->env;
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn);
    zdtun_ip_t source_ip = tuple->src_ip;
    zdtun_ip_t dest_ip = tuple->dst_ip;
    jbyteArray source = ip_bytes(env, &source_ip, tuple->ipver);
    jbyteArray destination = ip_bytes(env, &dest_ip, tuple->ipver);
    if (!source || !destination || (*env)->ExceptionCheck(env)) return 1;
    jobject flow = (*env)->CallObjectMethod(env, state->session, state->open_flow,
        source, (jint)ntohs(tuple->src_port), destination, (jint)ntohs(tuple->dst_port),
        (jint)tuple->ipproto);
    (*env)->DeleteLocalRef(env, source);
    (*env)->DeleteLocalRef(env, destination);
    if (!flow || (*env)->ExceptionCheck(env)) return 1;

    int denied = blocked(state, flow);
    jobject retained = NULL;
    if (denied) record(state, flow, 0, 1, 1);
    else if (!(*env)->ExceptionCheck(env)) retained = (*env)->NewGlobalRef(env, flow);
    (*env)->DeleteLocalRef(env, flow);
    if (!retained) return 1;
    zdtun_conn_set_userdata(conn, retained);
    return 0;
}

static void close_connection(zdtun_t *tun, const zdtun_conn_t *conn) {
    bridge *state = zdtun_userdata(tun);
    JNIEnv *env = state->env;
    jobject flow = zdtun_conn_get_userdata(conn);
    if (flow) (*env)->DeleteGlobalRef(env, flow);
}

static int protect_socket(zdtun_t *tun, socket_t socket) {
    bridge *state = zdtun_userdata(tun);
    JNIEnv *env = state->env;
    return (*env)->ExceptionCheck(env)
        || !(*env)->CallBooleanMethod(env, state->session, state->protect, (jint)socket);
}

static int send_client(zdtun_t *tun, zdtun_pkt_t *packet, const zdtun_conn_t *conn) {
    bridge *state = zdtun_userdata(tun);
    jobject flow = zdtun_conn_get_userdata(conn);
    if (blocked(state, flow)) return -1;
    ssize_t written;
    do {
        written = write(state->fd, packet->buf, packet->len);
    } while (written < 0 && errno == EINTR);
    if (written == packet->len) {
        record(state, flow, packet->len, 0, 0);
        return 0;
    }
    return -1;
}

static int close_blocked(zdtun_t *tun, const zdtun_conn_t *conn, void *data) {
    if (blocked(data, zdtun_conn_get_userdata(conn))) {
        zdtun_conn_close(tun, (zdtun_conn_t *)conn, CONN_STATUS_CLIENT_ERROR);
    }
    return 0;
}

static void forward_packet(zdtun_t *tun, bridge *state, char *buffer, int size) {
    zdtun_pkt_t packet;
    if (zdtun_parse_pkt(tun, buffer, size, &packet) != 0
            || (packet.flags & ZDTUN_PKT_IS_FRAGMENT)) return;
    const zdtun_5tuple_t *tuple = &packet.tuple;
    int create = tuple->ipproto != IPPROTO_TCP
        || ((packet.tcp->th_flags & (TH_SYN | TH_ACK)) == TH_SYN);
    zdtun_conn_t *conn = zdtun_lookup(tun, tuple, create);
    if (!conn) return;
    jobject flow = zdtun_conn_get_userdata(conn);
    if (blocked(state, flow)) {
        record(state, flow, size, 1, 1);
        zdtun_conn_close(tun, conn, CONN_STATUS_CLIENT_ERROR);
        return;
    }
    record(state, flow, size, 1, 0);
    if (zdtun_forward(tun, &packet, conn) != 0) {
        zdtun_conn_close(tun, conn, CONN_STATUS_ERROR);
    }
}

JNIEXPORT jint JNICALL Java_local_cc3_manager_FirewallService_00024Session_runTunnel(
        JNIEnv *env, jobject session, jint fd, jint mtu) {
    if (fd < 0 || fd >= FD_SETSIZE) {
        if (fd >= 0) close(fd);
        return EINVAL;
    }
    jclass type = (*env)->GetObjectClass(env, session);
    bridge state = {
        .env = env,
        .session = session,
        .fd = fd,
        .open_flow = (*env)->GetMethodID(env, type, "openFlow",
                "([BI[BII)Llocal/cc3/manager/TrafficLog$Flow;"),
        .blocked = (*env)->GetMethodID(env, type, "blocked",
                "(Llocal/cc3/manager/TrafficLog$Flow;)Z"),
        .record = (*env)->GetMethodID(env, type, "record",
                "(Llocal/cc3/manager/TrafficLog$Flow;IZZ)V"),
        .protect = (*env)->GetMethodID(env, type, "protectSocket", "(I)Z"),
        .running = (*env)->GetMethodID(env, type, "running", "()Z"),
        .revision = (*env)->GetMethodID(env, type, "revision", "()I")
    };
    zdtun_callbacks_t callbacks = {
        .send_client = send_client,
        .on_socket_open = protect_socket,
        .on_connection_open = open_connection,
        .on_connection_close = close_connection
    };
    zdtun_t *tun = (*env)->ExceptionCheck(env) ? NULL : zdtun_init(&callbacks, &state);
    if (!tun) {
        close(fd);
        return EIO;
    }
    zdtun_set_mtu(tun, mtu);

    // A TCP peer may close while a forwarding write is in progress.
    sigset_t pipe_signal;
    sigemptyset(&pipe_signal);
    sigaddset(&pipe_signal, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &pipe_signal, NULL);

    char buffer[65535];
    jint revision = -1;
    int result = 0;
    time_t next_purge = 0;
    while (!(*env)->ExceptionCheck(env)
            && (*env)->CallBooleanMethod(env, session, state.running)) {
        jint latest = (*env)->CallIntMethod(env, session, state.revision);
        if (latest != revision) {
            zdtun_iter_connections(tun, close_blocked, &state);
            revision = latest;
        }
        time_t now = time(NULL);
        if (now >= next_purge) {
            zdtun_purge_expired(tun);
            next_purge = now + 1;
        }
        fd_set readers, writers;
        int max_fd;
        zdtun_fds(tun, &max_fd, &readers, &writers);
        FD_SET(fd, &readers);
        if (fd > max_fd) max_fd = fd;
        struct timeval wait = {0, 150000};
        int ready = select(max_fd + 1, &readers, &writers, NULL, &wait);
        if (ready < 0) {
            if (errno == EINTR) continue;
            result = errno;
            break;
        }
        if (ready == 0 || !(*env)->CallBooleanMethod(env, session, state.running)
                || revision != (*env)->CallIntMethod(env, session, state.revision)) continue;

        // Handle the returned socket set before reading TUN, which can open new sockets.
        zdtun_handle_fd(tun, &readers, &writers);
        if (FD_ISSET(fd, &readers)) {
            ssize_t size = read(fd, buffer, sizeof(buffer));
            if (size > 0) forward_packet(tun, &state, buffer, size);
            else if (size == 0 || (errno != EAGAIN && errno != EINTR)) {
                result = size == 0 ? EPIPE : errno;
                break;
            }
        }
    }
    zdtun_finalize(tun);
    close(fd);
    return result;
}
