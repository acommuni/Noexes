package me.mdbell.noexs.io.usb;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import javax.usb.UsbConfiguration;
import javax.usb.UsbDevice;
import javax.usb.UsbEndpoint;
import javax.usb.UsbException;
import javax.usb.UsbInterface;
import javax.usb.UsbIrp;
import javax.usb.UsbPipe;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import me.mdbell.noexs.core.ConnectionException;
import me.mdbell.noexs.core.IConnection;

public class UsbConnection implements IConnection {

    private static final byte USB_INTERFACE = 0;

    // private static final byte READ_ENDPOINT = (byte) 0x83;
    // private static final byte WRITE_ENDPOINT = (byte) 0x03;

    private static final byte READ_ENDPOINT = (byte) 0x81;
    private static final byte WRITE_ENDPOINT = (byte) 0x81;

    private static final Logger logger = LogManager.getLogger(UsbConnection.class);

    private UsbDevice device;
    private UsbConfiguration cfg;
    private UsbInterface iface;
    private UsbEndpoint read, write;
    private UsbPipe readPipe, writePipe;

    private List<UsbIrp> outputIrps = new LinkedList<>();

    public UsbConnection(UsbDevice device) throws UsbException {
        this.device = device;
        init();
    }

    private void init() throws UsbException {
        cfg = device.getActiveUsbConfiguration();
        logger.debug("Active USB : {}", cfg);
        iface = cfg.getUsbInterface(USB_INTERFACE);
        boolean isClaimed = iface.isClaimed();
        logger.debug("USB interface: {}, already claimed : {} ", iface, isClaimed);
        if (!isClaimed) {
            logger.debug("Claiming USB");
            iface.claim();
        }
        try {
            List<UsbEndpoint> usbEndpoints = iface.getUsbEndpoints();

            for (UsbEndpoint us : usbEndpoints) {
                logger.debug("UsbEndpoint : {}", us.getUsbEndpointDescriptor());
            }

            read = iface.getUsbEndpoint(READ_ENDPOINT);
            write = iface.getUsbEndpoint(WRITE_ENDPOINT);
            readPipe = read.getUsbPipe();
            writePipe = write.getUsbPipe();

            readPipe.open();
            if (!writePipe.isOpen()) {
                writePipe.open();
            }

            logger.debug("Pipes opened");
        } catch (Throwable e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            throw e;
        }
    }

    @Override
    public boolean connected() {
        return readPipe.isOpen() && writePipe.isOpen();
    }

    @Override
    public void writeByte(int i) {
        UsbIrp irp = writePipe.createUsbIrp();
        irp.setData(new byte[] { (byte) i });
        outputIrps.add(irp);
    }

    @Override
    public void write(byte[] data, int off, int len) {
        UsbIrp irp = writePipe.createUsbIrp();
        irp.setData(data, off, len);
        outputIrps.add(irp);
    }

    @Override
    public int readByte() {
        byte[] b = { 0 };
        try {
            readPipe.syncSubmit(b);
        } catch (UsbException e) {
            throw new ConnectionException(e);
        }
        return b[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        UsbIrp irp = readPipe.createUsbIrp();
        irp.setData(b, off, len);
        try {
            readPipe.syncSubmit(irp);
        } catch (UsbException e) {
            throw new ConnectionException(e);
        }
        return irp.getActualLength();
    }

    @Override
    public void flush() {
        try {
            writePipe.syncSubmit(outputIrps);
            outputIrps.clear();
        } catch (UsbException e) {
            throw new ConnectionException(e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            readPipe.close();
            writePipe.close();
        } catch (UsbException e) {
            throw new IOException(e);
        }

        try {
            iface.release();
        } catch (UsbException e) {
            throw new IOException(e);
        }

    }
}
