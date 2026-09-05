package me.mdbell.noexs.ui.services;

import java.net.InetSocketAddress;
import java.net.Socket;

import javax.usb.UsbDevice;
import javax.usb.UsbException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import me.mdbell.noexs.core.IConnection;
import me.mdbell.noexs.io.net.SocketConnection;
import me.mdbell.noexs.io.usb.UsbConnection;
import me.mdbell.noexs.io.usb.UsbUtils;
import me.mdbell.noexs.ui.models.ConnectionType;

public class DebuggerConnectionService extends ScheduledService<IConnection> {

    private static final Logger logger = LogManager.getLogger(DebuggerConnectionService.class);

    private String host;
    private int port;
    private int timeout = 1000;
    private ConnectionType type;

    public DebuggerConnectionService() {
        super();
        setMaximumFailureCount(10);
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    @Override
    protected Task<IConnection> createTask() {
        switch (type) {
            case NETWORK:
                return new Task<>() {
                    @Override
                    protected IConnection call() throws Exception {
                        updateMessage("Connecting to:" + host + ":" + port + " (Attempt:"
                                + (getCurrentFailureCount() + 1) + "/" + getMaximumFailureCount() + ")");
                        Socket s = new Socket();
                        InetSocketAddress addr = new InetSocketAddress(host, port);
                        s.connect(addr, timeout);
                        s.setTcpNoDelay(true);
                        return new SocketConnection(s);
                    }
                };
            case USB:
                return new Task<>() {
                    @Override
                    protected IConnection call() {
                        UsbDevice switchDevice = null;

                        try {
                            switchDevice = UsbUtils.findSwitch();
                            if (switchDevice != null) {
                                logger.debug("Switch connected to USB found : {}", switchDevice);
                                updateMessage("Connecting to USB :" + switchDevice);
                                return new UsbConnection(switchDevice);
                            }
                        } catch (UsbException e) {
                            e.printStackTrace();
                        }

                        throw new UnsupportedOperationException("Unable to find switch on USB");
                    }
                };
            default:
                return new Task<>() {
                    @Override
                    protected IConnection call() {
                        throw new UnsupportedOperationException("Unsupported connection type:" + type);
                    }
                };
        }
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setType(ConnectionType type) {
        this.type = type;
        if (this.type != null) {
            setMaximumFailureCount(this.type.getMaxConnectionFailureCountt());
        }
    }
}
