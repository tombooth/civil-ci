
import java.util.Map;
import java.util.ArrayList;

import clojure.lang.IFn;
import clojure.lang.IDeref;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;

import org.httpkit.server.AsyncChannel;
import org.httpkit.server.HttpRequest;

public class FakeChannel extends AsyncChannel implements IDeref {

    public boolean isWebsocket;
    public boolean isOpen = true;
    public ArrayList<Object> sent = new ArrayList<Object>();

    private IFn closeCallback = null;

    static Keyword K_NORMAL = Keyword.intern("normal");
    static Keyword K_UNKNOWN = Keyword.intern("unknown");

    public FakeChannel(boolean isWebsocket) {
        super(null, null);
        this.isWebsocket = isWebsocket;
    }

    public void sendHandshake(Map<String, Object> headers) {
    }

    public void onCloseHandler(IFn callback) {
        this.closeCallback = callback;
        if (!this.isOpen) {
            callback.invoke(K_UNKNOWN);
        }
    }

    public boolean send(Object data, boolean closeAfterSend) {
        sent.add(data);
        if (closeAfterSend) this.close();
        return true;
    }

    public void close() {
        this.isOpen = false;

        if (closeCallback != null) {
            this.closeCallback.invoke(K_NORMAL);
        }
    }

    public Object deref() {
        return PersistentVector.create(sent);
    }

    // Neuter AsyncChannel's public functions

    public void reset(HttpRequest request) {}
    public void setReceiveHandler(IFn fn) {}
    public void messageReceived(final Object mesg) {}
    public void setCloseHandler(IFn fn) {}
    public void onClose(int status) {}
    public boolean serverClose(int status) { return true; }
    public String toString() { return "FakeChannel"; }
    public boolean isWebSocket() { return isWebsocket; }
    public boolean isClosed() { return !isOpen; }

}

