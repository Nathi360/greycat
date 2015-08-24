package org.kevoree.modeling.drivers.websocket;

import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSocketVersion;
import io.undertow.websockets.core.WebSockets;
import org.kevoree.modeling.KCallback;
import org.kevoree.modeling.KConfig;
import org.kevoree.modeling.cdn.KContentDeliveryDriver;
import org.kevoree.modeling.cdn.KContentUpdateListener;
import org.kevoree.modeling.memory.chunk.KIntMapCallBack;
import org.kevoree.modeling.memory.chunk.impl.ArrayIntMap;
import org.kevoree.modeling.memory.chunk.impl.ArrayLongMap;
import org.kevoree.modeling.message.KMessage;
import org.kevoree.modeling.message.impl.Message;
import org.xnio.BufferAllocator;
import org.xnio.ByteBufferSlicePool;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

public class WebSocketCDNClient extends AbstractReceiveListener implements KContentDeliveryDriver {

    private static final int CALLBACK_SIZE = 100000;

    private UndertowWSClient _client;

    private AtomicInteger _atomicInteger = null;

    private final ArrayLongMap<Object> _callbacks = new ArrayLongMap<Object>(KConfig.CACHE_INIT_SIZE, KConfig.CACHE_LOAD_FACTOR);

    public WebSocketCDNClient(String url) {
        _client = new UndertowWSClient(url);
    }

    @Override
    public void connect(KCallback<Throwable> callback) {
        _client.connect(this);
        _atomicInteger = new AtomicInteger();
        callback.on(null);
    }

    @Override
    public void close(KCallback<Throwable> callback) {
        _client.close();
        callback.on(null);
    }

    private int nextKey() {
        return _atomicInteger.getAndUpdate(new IntUnaryOperator() {
            @Override
            public int applyAsInt(int operand) {
                if (operand == CALLBACK_SIZE) {
                    return 0;
                } else {
                    return operand + 1;
                }
            }
        });
    }

    @Override
    protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) throws IOException {
        String payload = message.getData();
        KMessage msg = Message.load(payload);
        switch (msg.type()) {
            case Message.GET_RES_TYPE: {
                Object callbackRegistered = _callbacks.get(msg.id());
                if (callbackRegistered != null) {
                    ((KCallback) callbackRegistered).on(msg.values());
                } else {
                    System.err.println();
                }
                _callbacks.remove(msg.id());
            }
            break;
            case Message.PUT_RES_TYPE: {
                Object callbackRegistered = _callbacks.get(msg.id());
                if (callbackRegistered != null) {
                    ((KCallback) callbackRegistered).on(null);
                } else {
                    System.err.println();
                }
            }
            break;
            case Message.ATOMIC_GET_INC_RESULT_TYPE: {
                Object callbackRegistered = _callbacks.get(msg.id());
                if (callbackRegistered != null) {
                    ((KCallback) callbackRegistered).on(Short.parseShort(msg.values()[0]));
                } else {
                    System.err.println();
                }
            }
            break;
            case Message.EVENTS_TYPE: {
                if (additionalInterceptors != null) {
                    additionalInterceptors.each(new KIntMapCallBack<KContentUpdateListener>() {
                        @Override
                        public void on(int key, KContentUpdateListener value) {
                            value.on(msg.keys());
                        }
                    });
                }
            }
            break;
            default: {
                System.err.println("MessageType not supported:" + msg.type());
            }
        }
    }

    @Override
    public void atomicGetIncrement(long[] key, KCallback<Short> callback) {
        KMessage atomicGetRequest = new Message();
        atomicGetRequest.setType(Message.ATOMIC_GET_INC_REQUEST_TYPE);
        atomicGetRequest.setID(nextKey());
        atomicGetRequest.setKeys(key);
        _callbacks.put(atomicGetRequest.id(), callback);
        WebSockets.sendText(atomicGetRequest.json(), _client.getChannel(), null);
    }

    @Override
    public void get(long[] keys, KCallback<String[]> callback) {
        KMessage getRequest = new Message();
        getRequest.setType(Message.GET_REQ_TYPE);
        getRequest.setKeys(keys);
        getRequest.setID(nextKey());
        _callbacks.put(getRequest.id(), callback);
        WebSockets.sendText(getRequest.json(), _client.getChannel(), null);
    }

    @Override
    public synchronized void put(long[] p_keys, String[] p_values, KCallback<Throwable> p_callback, int excludeListener) {
        KMessage putRequest = new Message();
        putRequest.setType(Message.PUT_REQ_TYPE);
        putRequest.setKeys(p_keys);
        putRequest.setValues(p_values);
        putRequest.setID(nextKey());
        _callbacks.put(putRequest.id(), p_callback);
        WebSockets.sendText(putRequest.json(), _client.getChannel(), null);
        if (additionalInterceptors != null) {
            additionalInterceptors.each(new KIntMapCallBack<KContentUpdateListener>() {
                @Override
                public void on(int key, KContentUpdateListener value) {
                    if (value != null && key != excludeListener) {
                        value.on(p_keys);
                    }
                }
            });
        }
    }

    @Override
    public void remove(long[] keys, KCallback<Throwable> error) {
        //TODO
    }

    private ArrayIntMap<KContentUpdateListener> additionalInterceptors = null;

    private Random random = new Random();

    private int nextListenerID() {
        return random.nextInt();
    }

    @Override
    public synchronized int addUpdateListener(KContentUpdateListener p_interceptor) {
        if (additionalInterceptors == null) {
            additionalInterceptors = new ArrayIntMap<KContentUpdateListener>(KConfig.CACHE_INIT_SIZE, KConfig.CACHE_LOAD_FACTOR);
        }
        int newID = nextListenerID();
        additionalInterceptors.put(newID, p_interceptor);
        return newID;
    }

    @Override
    public synchronized void removeUpdateListener(int id) {
        if (additionalInterceptors != null) {
            additionalInterceptors.remove(id);
        }
    }

    class UndertowWSClient {

        private ByteBufferSlicePool _buffer;
        private XnioWorker _worker;
        private WebSocketChannel _webSocketChannel = null;
        private String _url;

        public UndertowWSClient(String url) {
            this._url = url;
            try {
                Xnio xnio = Xnio.getInstance(io.undertow.websockets.client.WebSocketClient.class.getClassLoader());
                _worker = xnio.createWorker(OptionMap.builder()
                        .set(Options.WORKER_IO_THREADS, 2)
                        .set(Options.CONNECTION_HIGH_WATER, 1000000)
                        .set(Options.CONNECTION_LOW_WATER, 1000000)
                        .set(Options.WORKER_TASK_CORE_THREADS, 30)
                        .set(Options.WORKER_TASK_MAX_THREADS, 30)
                        .set(Options.TCP_NODELAY, true)
                        .set(Options.CORK, true)
                        .getMap());

                _buffer = new ByteBufferSlicePool(BufferAllocator.BYTE_BUFFER_ALLOCATOR, 1024, 1024);


            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void connect(AbstractReceiveListener listener) {
            try {
                _webSocketChannel = io.undertow.websockets.client.WebSocketClient.connect(_worker, _buffer, OptionMap.EMPTY, new URI(_url), WebSocketVersion.V13).get();
                _webSocketChannel.getReceiveSetter().set(listener);
                _webSocketChannel.resumeReceives();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }

        public void close() {
            try {
                _webSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public WebSocketChannel getChannel() {
            return _webSocketChannel;
        }

    }

    @Override
    public String[] peers() {
        return new String[0];
    }

    @Override
    public void sendToPeer(String peer, KMessage message) {
        //NOOP, TODO P2P execution here
    }

}
