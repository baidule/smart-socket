package net.vinote.smart.socket.transport.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;

import net.vinote.smart.socket.extension.cluster.Client2ClusterMessageProcessor;
import net.vinote.smart.socket.lang.QuicklyConfig;
import net.vinote.smart.socket.lang.StringUtils;
import net.vinote.smart.socket.logger.RunLogger;
import net.vinote.smart.socket.transport.ChannelServiceStatus;
import net.vinote.smart.socket.transport.SessionStatus;

/**
 * NIO服务器
 *
 * @author Seer
 *
 */
public final class NioQuickServer extends AbstractChannelService {
	private ServerSocketChannel server;

	public NioQuickServer(final QuicklyConfig config) {
		super(config);
	}

	/**
	 * 接受并建立客户端与服务端的连接
	 *
	 * @param key
	 * @param selector
	 * @throws IOException
	 */
	@Override
	protected void acceptConnect(final SelectionKey key, final Selector selector) throws IOException {

		ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
		SocketChannel socketChannel = serverChannel.accept();
		socketChannel.configureBlocking(false);
		SelectionKey socketKey = socketChannel.register(selector, SelectionKey.OP_READ);
		NioSession session = null;
		// 判断当前链路的消息是否交由集群服务器处理
		if (config.getClusterTriggerStrategy() != null && config.getClusterTriggerStrategy().cluster()) {
			session = new NioSession(socketKey, config, Client2ClusterMessageProcessor.getInstance());
		} else {
			session = new NioSession(socketKey, config);
		}
		socketKey.attach(session);
		socketChannel.finishConnect();
	}

	@Override
	protected void exceptionInSelectionKey(SelectionKey key, final Exception e) throws Exception {
		RunLogger.getLogger().log(Level.WARNING, "Close Channel because of Exception", e);
		final Object att = key.attach(null);
		if (att instanceof NioSession) {
			((NioSession) att).close();
		}
		key.channel().close();
		RunLogger.getLogger().log(Level.SEVERE, "close connection " + key.channel());
		key.cancel();
	}

	@Override
	protected void exceptionInSelector(Exception e) {
		RunLogger.getLogger().log(Level.WARNING, e.getMessage(), e);
	}

	@Override
	protected void readFromChannel(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		NioSession session = (NioSession) key.attachment();
		ByteBuffer buffer = session.getReadBuffer();
		int readSize = 0;
		int loopTimes = READ_LOOP_TIMES;// 轮训次数,以便及时让出资源
		do {
			session.flushReadBuffer();
		} while ((key.interestOps() & SelectionKey.OP_READ) > 0 && --loopTimes > 0
			&& (readSize = socketChannel.read(buffer)) > 0);// 读取管道中的数据块
		// 达到流末尾则注销读关注
		if (readSize == -1 || session.getStatus() == SessionStatus.Closing) {
			RunLogger.getLogger().log(Level.SEVERE, "注销客户端[" + socketChannel + "]读关注");
			key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
		}
	}

	public void shutdown() {
		updateServiceStatus(ChannelServiceStatus.STOPPING);
		config.getProcessor().shutdown();
		try {
			if (selector != null) {
				selector.close();
				selector.wakeup();
			}
		} catch (final IOException e1) {
			e1.printStackTrace();
		}
		try {
			server.close();
		} catch (final IOException e) {
			e.printStackTrace();
		}
		Client2ClusterMessageProcessor.getInstance().shutdown();
	}

	public void start() throws IOException {
		try {
			assertAbnormalStatus();
			updateServiceStatus(ChannelServiceStatus.STARTING);
			server = ServerSocketChannel.open();
			server.configureBlocking(false);
			InetSocketAddress address = null;
			if (StringUtils.isBlank(config.getLocalIp())) {
				address = new InetSocketAddress(config.getPort());
			} else {
				address = new InetSocketAddress(config.getLocalIp(), config.getPort());
			}
			server.socket().bind(address);
			selector = Selector.open();
			server.register(selector, SelectionKey.OP_ACCEPT, config);
			serverThread = new Thread(this, "Nio-Server");
			serverThread.start();
			if (config.getClusterUrl() != null) {
				// 启动集群服务
				// new Thread(new Runnable() {
				//
				// public void run() {
				try {
					Client2ClusterMessageProcessor.getInstance().init(config);
					RunLogger.getLogger().log(Level.SEVERE, "Start Cluster Service...");
				} catch (final Exception e) {
					RunLogger.getLogger().log(Level.WARNING, "", e);
				}
				//
				// }
				// }).start();
				;
			}
		} catch (final IOException e) {
			shutdown();
			throw e;
		}
	}

	@Override
	protected void writeToChannel(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		NioSession session = (NioSession) key.attachment();
		ByteBuffer buffer;
		int loopTimes = WRITE_LOOP_TIMES;// 轮训次数,一遍及时让出资源
		// buffer = session.getByteBuffer()若读取不到数据,则内部会移除写关注
		// socketChannel.write(buffer) == 0则表示当前以不可写
		while ((buffer = session.getWriteBuffer()) != null && socketChannel.write(buffer) > 0 && --loopTimes > 0)
			;
		if (session.getStatus() == SessionStatus.Closing && (buffer = session.getWriteBuffer()) == null) {
			session.close();
		}
	}
}