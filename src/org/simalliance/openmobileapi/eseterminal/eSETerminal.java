package org.simalliance.openmobileapi.eseterminal;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.INfcAdapterExtras;
import android.nfc.NfcAdapter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;

import java.util.NoSuchElementException;

import org.simalliance.openmobileapi.internal.ByteArrayConverter;
import org.simalliance.openmobileapi.service.OpenLogicalChannelResponse;
import org.simalliance.openmobileapi.service.SmartcardError;
import org.simalliance.openmobileapi.service.ITerminalService;
import org.simalliance.openmobileapi.util.CommandApdu;
import org.simalliance.openmobileapi.util.ISO7816;
import org.simalliance.openmobileapi.util.ResponseApdu;

public final class eSETerminal extends Service {

    private static final String TAG = "eSETerminal";

    public static final String ESE_TERMINAL = "eSE";

    private static final String ACTION_ESE_STATE_CHANGED = "org.simalliance.openmobileapi.action.ESE_STATE_CHANGED";
    private static final String PKG_NAME = "org.simalliance.openmobileapi.eseterminal";

    /**
     * Used for communicating with NFC Execution Environment (eSE).
     */
    private INfcAdapterExtras mNfcExtras;

    /**
     * Used for communicating with NFC extras.
     */
    private Binder mBinder = new Binder();

    private BroadcastReceiver mNfcReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        return new TerminalServiceImplementation();
    }

    @Override
    public void onCreate() {
        registerAdapterStateChangedEvent();
        init();
    }

    private void init() {
        NfcAdapter adapter =  NfcAdapter.getDefaultAdapter(this);
        if(adapter == null) {
            return;
        }
        mNfcExtras = adapter.getNfcAdapterExtrasInterface();
        if(mNfcExtras == null)  {
            return;
        }
        try {
            Bundle b = mNfcExtras.open(PKG_NAME, mBinder);
            if (!isSuccess(b)) {
                Log.e(TAG, "Error opening NFC extras");
                mNfcExtras = null;
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error while opening nfc adapter", e);
            mNfcExtras = null;
            return;
        }
    }

    @Override
    public void onDestroy() {
        if (mNfcExtras != null) {
            try {
                Bundle b = mNfcExtras.close(PKG_NAME, mBinder);
                if (!isSuccess(b)) {
                    Log.e(TAG, "Close SE failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error while closing nfc adapter", e);
            }
        }
        unregisterAdapterStateChangedEvent();
        super.onDestroy();
    }

    public static String getType() {
        return ESE_TERMINAL;
    }

    private byte[] transmit(byte[] cmd) throws Exception {
        if (!isCardPresent()) {
            throw new IllegalStateException("Open SE failed");
        }
        Log.d(TAG, "> " + ByteArrayConverter.byteArrayToHexString(cmd));
        Bundle b = mNfcExtras.transceive(PKG_NAME, cmd);
        if (!isSuccess(b)) {
            throw new IOException ("Exchange APDU failed");
        }
        byte[] response = b.getByteArray("out");
        Log.d(TAG, "< " + ByteArrayConverter.byteArrayToHexString(response));
        return response;
    }

    private void registerAdapterStateChangedEvent() {
        Log.v(TAG, "register ADAPTER_STATE_CHANGED event");

        IntentFilter intentFilter = new IntentFilter(
                "android.nfc.action.ADAPTER_STATE_CHANGED");
        mNfcReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final boolean nfcAdapterAction = "android.nfc.action.ADAPTER_STATE_CHANGED".equals(
                        intent.getAction());
                // Is NFC Adapter turned on?
                final boolean nfcAdapterOn
                        = nfcAdapterAction && intent.getIntExtra(
                        "android.nfc.extra.ADAPTER_STATE", 1) == 3;
                if (nfcAdapterOn) {
                    Log.i(TAG, "NFC Adapter is ON. Checking access rules for"
                            + " updates.");
                    Intent i = new Intent(ACTION_ESE_STATE_CHANGED);
                    sendBroadcast(i);
                }
            }
        };
        registerReceiver(mNfcReceiver, intentFilter);
    }

    private void unregisterAdapterStateChangedEvent() {
        if (mNfcReceiver != null) {
            Log.v(TAG, "unregister ADAPTER_STATE_CHANGED event");
            unregisterReceiver(mNfcReceiver);
            mNfcReceiver = null;
        }
    }

    private boolean isSuccess(Bundle b) {
        return b != null && b.getInt("e") == 0;
    }

    private boolean isCardPresent() {
        return mNfcExtras != null;
    }
    /**
     * The Terminal service interface implementation.
     */
    final class TerminalServiceImplementation extends ITerminalService.Stub {
        @Override
        public String getType() {
            return ESE_TERMINAL;
        }


        @Override
        public OpenLogicalChannelResponse internalOpenLogicalChannel(
                byte[] aid,
                byte p2,
                SmartcardError error) throws RemoteException {
            try {
                if (!isCardPresent()) {
                    throw new IllegalStateException("Open SE failed");
                }
                CommandApdu manageChannelCommand = new CommandApdu(
                        ISO7816.CLA_INTERINDUSTRY,
                        ISO7816.INS_MANAGE_CHANNEL,
                        (byte) 0x00,
                        (byte) 0x00,
                        (byte) 0x01);
                ResponseApdu resp = new ResponseApdu(
                        eSETerminal.this.transmit(manageChannelCommand.toByteArray()));
                if (resp.getSwValue() != ISO7816.SW_NO_FURTHER_QUALIFICATION) {
                    switch (resp.getSwValue()) {
                        case ISO7816.SW_LOGICAL_CHANNEL_NOT_SUPPORTED:
                            throw new NoSuchElementException("Logical channels not supported");
                        case ISO7816.SW_FUNC_NOT_SUPPORTED:
                            throw new NoSuchElementException("no free channel available");
                        default:
                            throw new NoSuchElementException("Error sending manage channel open: "
                                    + ByteArrayConverter.byteArrayToHexString(resp.getSw()));
                    }
                }

                if (resp.getData().length != 1) {
                    throw new NoSuchElementException("unsupported MANAGE CHANNEL response data");
                }
                int channelNumber = resp.getData()[0] & 0xFF;
                if (channelNumber == 0 || channelNumber > 19) {
                    throw new NoSuchElementException("invalid logical channel number returned");
                }

                byte[] selectResponse = null;
                if (aid != null) {
                    byte cla = ISO7816.CLA_INTERINDUSTRY;
                    if (channelNumber < 4) {
                        cla |= channelNumber;
                    } else {
                        cla |= (0x40 | (channelNumber - 4));
                    }
                    CommandApdu selectCommand = new CommandApdu(
                            cla,
                            ISO7816.INS_SELECT,
                            (byte) 0x04,
                            p2,
                            aid,
                            (byte) 0x00);
                    try {
                        selectResponse = eSETerminal.this.transmit(selectCommand.toByteArray());
                        resp = new ResponseApdu(selectResponse);
                        if (resp.getSwValue() != ISO7816.SW_NO_FURTHER_QUALIFICATION
                                && resp.getSw1Value() != 0x62
                                && resp.getSw1Value() != 0x63) {
                            throw new NoSuchElementException("Select command failed");
                        }
                    } catch (Exception e) {
                        internalCloseLogicalChannel(channelNumber, error);
                        throw e;
                    }
                }

                return new OpenLogicalChannelResponse(channelNumber, selectResponse);
            } catch (Exception e) {
                Log.e(TAG, "Error during internalOpenLogicalChannel", e);
                error.set(e);
                return null;
            }
        }

        @Override
        public void internalCloseLogicalChannel(int channelNumber, SmartcardError error)
                throws RemoteException {
            try {
                if (!isCardPresent()) {
                    // Try to initialize it again
                    init();
                    if (!isCardPresent()) {
                        throw new IOException("open SE failed");
                    }
                }
                if (channelNumber > 0) {
                    CommandApdu manageChannelClose = new CommandApdu(
                            ISO7816.CLA_INTERINDUSTRY,
                            ISO7816.INS_MANAGE_CHANNEL,
                            (byte) 0x80,
                            (byte) channelNumber);
                    eSETerminal.this.transmit(manageChannelClose.toByteArray());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during internalCloseLogicalChannel", e);
                error.set(e);
            }
        }

        @Override
        public byte[] internalTransmit(byte[] command, SmartcardError error) throws RemoteException {
            try {
                return eSETerminal.this.transmit(command);
            } catch (Exception e) {
                error.set(e);
                Log.e(TAG, "Error during internalTransmit", e);
                return null;
            }
        }

        @Override
        public byte[] getAtr() {
            return null;
        }

        @Override
        public boolean isCardPresent() throws RemoteException {
            return eSETerminal.this.isCardPresent();
        }

        @Override
        public byte[] simIOExchange(int fileID, String filePath, byte[] cmd, SmartcardError error)
                throws RemoteException {
            throw new RemoteException("SIM IO error!");
        }

        @Override
        public String getSeStateChangedAction() {
            return ACTION_ESE_STATE_CHANGED;
        }
    }
}
