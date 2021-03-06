package net.vinote.demo;

import org.smartboot.socket.service.process.MessageProcessor;
import org.smartboot.socket.transport.AioSession;

/**
 * @author Seer
 * @version V1.0 , 2017/8/23
 */
public class IntegerClientProcessor implements MessageProcessor<Integer> {
    private AioSession<Integer> session;

    @Override
    public void process(AioSession<Integer> session, Integer msg) throws Exception {
        System.out.println("接受到服务端响应数据：" + msg);
    }

    @Override
    public void initSession(AioSession<Integer> session) {
        this.session = session;
    }

    public AioSession<Integer> getSession() {
        return session;
    }
}
