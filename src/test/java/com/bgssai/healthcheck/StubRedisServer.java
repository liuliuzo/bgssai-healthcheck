package com.bgssai.healthcheck;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 说 RESP 协议的假 Redis，用来在没有真实 Redis 的环境里跑探针用例。
 *
 * <p>只实现探针会用到的四条命令（AUTH / SELECT / PING / INFO），并把收到的命令记下来，
 * 好让用例断言「口令确实发出去了」以及「命令顺序对不对」。</p>
 */
public final class StubRedisServer implements AutoCloseable {

    private final ServerSocket server;

    private final Thread acceptor;

    private final List<String> received = new CopyOnWriteArrayList<>();

    private final String password;

    private final String info;

    private volatile boolean running = true;

    public StubRedisServer(String password, String info) {
        this.password = password;
        this.info = info;
        try {
            this.server = new ServerSocket();
            this.server.bind(new InetSocketAddress("127.0.0.1", 0));
        }
        catch (IOException ex) {
            throw new IllegalStateException("无法启动测试用 Redis 服务", ex);
        }
        this.acceptor = new Thread(this::acceptLoop, "stub-redis");
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    public int port() {
        return this.server.getLocalPort();
    }

    public String url() {
        return "redis://127.0.0.1:" + port();
    }

    /** 服务端实际收到的命令，元素形如 {@code PING} 或 {@code AUTH secret}。 */
    public List<String> received() {
        return List.copyOf(this.received);
    }

    private void acceptLoop() {
        while (this.running) {
            try (Socket socket = this.server.accept()) {
                serve(socket);
            }
            catch (IOException ex) {
                // 关闭时 accept 会抛异常，属正常退出路径
                if (this.running) {
                    continue;
                }
                return;
            }
        }
    }

    private void serve(Socket socket) throws IOException {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        while (true) {
            List<String> args = readCommand(in);
            if (args.isEmpty()) {
                return;
            }
            this.received.add(String.join(" ", args));
            String name = args.getFirst().toUpperCase(java.util.Locale.ROOT);
            switch (name) {
                case "AUTH" -> write(out, this.password != null && this.password.equals(args.getLast())
                        ? "+OK\r\n"
                        : "-WRONGPASS invalid username-password pair\r\n");
                case "SELECT", "PING" -> write(out, "PING".equals(name) ? "+PONG\r\n" : "+OK\r\n");
                case "INFO" -> {
                    byte[] payload = this.info.getBytes(StandardCharsets.UTF_8);
                    write(out, "$" + payload.length + "\r\n" + this.info + "\r\n");
                }
                default -> write(out, "-ERR unknown command\r\n");
            }
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 只解析探针会发的数组形式请求，够用即可。 */
    private static List<String> readCommand(InputStream in) throws IOException {
        String header = readLine(in);
        if (header == null || header.isEmpty() || header.charAt(0) != '*') {
            return List.of();
        }
        int count = Integer.parseInt(header.substring(1).trim());
        List<String> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String lengthLine = readLine(in);
            if (lengthLine == null || lengthLine.isEmpty()) {
                return List.of();
            }
            int length = Integer.parseInt(lengthLine.substring(1).trim());
            byte[] value = in.readNBytes(length);
            in.readNBytes(2);
            args.add(new String(value, StandardCharsets.UTF_8));
        }
        return args;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int current;
        while ((current = in.read()) != -1) {
            if (current == '\r') {
                in.read();
                return sb.toString();
            }
            sb.append((char) current);
        }
        return null;
    }

    @Override
    public void close() {
        this.running = false;
        try {
            this.server.close();
        }
        catch (IOException ignored) {
            // 关闭失败无所谓，进程退出时端口自然释放
        }
        this.acceptor.interrupt();
    }
}
