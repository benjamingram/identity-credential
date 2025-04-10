/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.identity;

import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.model.SimpleValue;

@SuppressWarnings("deprecation")
@RunWith(AndroidJUnit4.class)
public class NfcEnagementHelperTest {
    private static final String TAG = "NfcEnagementHelperTest";

    @Test
    @SmallTest
    public void testStaticHandover() throws Exception {
        NfcEngagementHelper helper = null;
        Context context = InstrumentationRegistry.getTargetContext();
        IdentityCredentialStore store = Util.getIdentityCredentialStore(context);
        assumeTrue(store.getFeatureVersion() >= IdentityCredentialStore.FEATURE_VERSION_202201);

        PresentationSession session = store.createPresentationSession(
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);

        NfcEngagementHelper.Listener listener = new NfcEngagementHelper.Listener() {
            @Override
            public void onDeviceConnecting() {

            }

            @Override
            public void onDeviceConnected(DataTransport transport) {
            }

            @Override
            public void onError(@NonNull Throwable error) {

            }
        };

        BlockingQueue<byte[]> apdusSentByHelper = new ArrayBlockingQueue<byte[]>(100);
        NfcApduRouter apduRouter = new NfcApduRouter() {
            @Override
            public void sendResponseApdu(@NonNull byte[] responseApdu) {
                Log.w(TAG, "apdu: " + Util.toHex(responseApdu));
                apdusSentByHelper.add(responseApdu);
            }
        };

        Executor executor = Executors.newSingleThreadExecutor();
        NfcEngagementHelper.Builder builder = new NfcEngagementHelper.Builder(
                context,
                session,
                new DataTransportOptions.Builder().build(),
                apduRouter,
                listener,
                executor);

        // Include all ConnectionMethods that can exist in OOB data
        List<ConnectionMethod> connectionMethods = new ArrayList<>();
        UUID bleUuid = UUID.randomUUID();
        connectionMethods.add(new ConnectionMethodBle(
                true,
                true,
                bleUuid,
                bleUuid));
        connectionMethods.add(new ConnectionMethodNfc(
                0xffff,
                0xffff));
        connectionMethods.add(new ConnectionMethodWifiAware(
                null,
                OptionalLong.empty(),
                OptionalLong.empty(),
                null));
        builder.useStaticHandover(connectionMethods);
        helper = builder.build();
        helper.testingDoNotStartTransports();

        // Select Type 4 Tag NDEF app
        byte[] ndefAppId = NfcApduRouter.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION;
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduApplicationSelect(ndefAppId));
        byte[] responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu);

        // Select CC file
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduSelectFile(NfcUtil.CAPABILITY_CONTAINER_FILE_ID));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu);

        // Get CC file
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduReadBinary(0, 15));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        // The response is the CC file followed by STATUS_WORD_OK. Keep in sync with
        // NfcEngagementHelper.handleSelectFile() for the contents.
        Assert.assertEquals("000f207fff7fff0406e1047fff00ff9000", Util.toHex(responseApdu));

        // Select NDEF file
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduSelectFile(NfcUtil.NDEF_FILE_ID));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu);

        // Get length of Initial NDEF message
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduReadBinary(0, 2));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
        // don't know the length.
        Assert.assertEquals(4, responseApdu.length);
        Assert.assertEquals(0x90, responseApdu[2] & 0xff);
        Assert.assertEquals(0x00, responseApdu[3] & 0xff);
        int initialNdefMessageSize = (((int) responseApdu[0]) & 0xff) * 0x0100
                + (((int) responseApdu[1]) & 0xff);

        // Read Initial NDEF message
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduReadBinary(2, initialNdefMessageSize));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
        // don't know the length.
        Assert.assertEquals(initialNdefMessageSize + 2, responseApdu.length);
        Assert.assertEquals(0x90, responseApdu[initialNdefMessageSize] & 0xff);
        Assert.assertEquals(0x00, responseApdu[initialNdefMessageSize + 1] & 0xff);
        byte[] initialNdefMessage = Arrays.copyOf(responseApdu, responseApdu.length - 2);

        // The Initial NDEF message should contain Handover Select. Check this.
        NfcUtil.ParsedHandoverSelectMessage hs = NfcUtil.parseHandoverSelectMessage(initialNdefMessage);
        Assert.assertNotNull(hs);
        EngagementParser parser = new EngagementParser(hs.encodedDeviceEngagement);
        // Check the returned DeviceEngagement
        EngagementParser.Engagement e = parser.parse();
        Assert.assertEquals("1.0", e.getVersion());
        Assert.assertEquals(0, e.getOriginInfos().size());
        Assert.assertEquals(session.getEphemeralKeyPair().getPublic(), e.getESenderKey());
        Assert.assertEquals(0, e.getConnectionMethods().size());
        // Check the synthesized ConnectionMethod (from returned OOB data in HS)
        Assert.assertEquals(connectionMethods.size(), hs.connectionMethods.size());
        for (int n = 0; n < connectionMethods.size(); n++) {
            Assert.assertEquals(connectionMethods.get(n).toString(),
                    hs.connectionMethods.get(n).toString());
        }

        // Checks that the helper returns the correct DE and Handover
        byte[] expectedHandover = Util.cborEncode(new CborBuilder()
                .addArray()
                .add(initialNdefMessage)   // Handover Select message
                .add(SimpleValue.NULL)     // Handover Request message
                .end()
                .build().get(0));
        Assert.assertArrayEquals(expectedHandover, helper.getHandover());
        Assert.assertArrayEquals(hs.encodedDeviceEngagement, helper.getDeviceEngagement());

        helper.close();
    }

    @Test
    @SmallTest
    public void testNegotiatedHandover() throws Exception {
        NfcEngagementHelper helper = null;
        Context context = InstrumentationRegistry.getTargetContext();
        IdentityCredentialStore store = Util.getIdentityCredentialStore(context);
        assumeTrue(store.getFeatureVersion() >= IdentityCredentialStore.FEATURE_VERSION_202201);

        PresentationSession session = store.createPresentationSession(
                IdentityCredentialStore.CIPHERSUITE_ECDHE_HKDF_ECDSA_WITH_AES_256_GCM_SHA256);

        NfcEngagementHelper.Listener listener = new NfcEngagementHelper.Listener() {
            @Override
            public void onDeviceConnecting() {

            }

            @Override
            public void onDeviceConnected(DataTransport transport) {
            }

            @Override
            public void onError(@NonNull Throwable error) {
            }
        };

        BlockingQueue<byte[]> apdusSentByHelper = new ArrayBlockingQueue<byte[]>(100);
        NfcApduRouter apduRouter = new NfcApduRouter() {
            @Override
            public void sendResponseApdu(@NonNull byte[] responseApdu) {
                Log.w(TAG, "apdu: " + Util.toHex(responseApdu));
                apdusSentByHelper.add(responseApdu);
            }
        };

        Executor executor = Executors.newSingleThreadExecutor();
        NfcEngagementHelper.Builder builder = new NfcEngagementHelper.Builder(
                context,
                session,
                new DataTransportOptions.Builder().build(),
                apduRouter,
                listener,
                executor);

        // Include all ConnectionMethods that can exist in OOB data
        List<ConnectionMethod> connectionMethods = new ArrayList<>();
        UUID bleUuid = UUID.randomUUID();
        connectionMethods.add(new ConnectionMethodBle(
                true,
                true,
                bleUuid,
                bleUuid));
        connectionMethods.add(new ConnectionMethodNfc(
                0xffff,
                0xffff));
        connectionMethods.add(new ConnectionMethodWifiAware(
                null,
                OptionalLong.empty(),
                OptionalLong.empty(),
                null));
        builder.useNegotiatedHandover();
        helper = builder.build();
        helper.testingDoNotStartTransports();

        // Select Type 4 Tag NDEF app
        byte[] ndefAppId = NfcApduRouter.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION;
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduApplicationSelect(ndefAppId));
        byte[] responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu);

        // Select CC file
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduSelectFile(NfcUtil.CAPABILITY_CONTAINER_FILE_ID));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu);

        // Get length of CC file
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduReadBinary(0, 2));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        // The response contains the length as 2 bytes followed by STATUS_WORD_OK
        Assert.assertArrayEquals(Util.fromHex("000f9000"), responseApdu);

        // Get CC file
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduReadBinary(0, 15));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        // The response is the CC file followed by STATUS_WORD_OK. Keep in sync with
        // NfcEngagementHelper.handleSelectFile() for the contents.
        Assert.assertEquals("000f207fff7fff0406e1047fff00009000", Util.toHex(responseApdu));

        // Select NDEF file
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduSelectFile(NfcUtil.NDEF_FILE_ID));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        Assert.assertArrayEquals(NfcUtil.STATUS_WORD_OK, responseApdu);

        // Get length of Initial NDEF message
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduReadBinary(0, 2));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
        // don't know the length.
        Assert.assertEquals(4, responseApdu.length);
        Assert.assertEquals(0x90, responseApdu[2] & 0xff);
        Assert.assertEquals(0x00, responseApdu[3] & 0xff);
        int initialNdefMessageSize = (((int) responseApdu[0]) & 0xff) * 0x0100
                + (((int) responseApdu[1]) & 0xff);

        // Read Initial NDEF message
        apduRouter.addReceivedApdu(ndefAppId, NfcUtil.createApduReadBinary(2, initialNdefMessageSize));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
        // don't know the length.
        Assert.assertEquals(initialNdefMessageSize + 2, responseApdu.length);
        Assert.assertEquals(0x90, responseApdu[initialNdefMessageSize] & 0xff);
        Assert.assertEquals(0x00, responseApdu[initialNdefMessageSize + 1] & 0xff);
        byte[] initialNdefMessage = Arrays.copyOf(responseApdu, responseApdu.length - 2);

        // The Initial NDEF message should contain a Service Parameter record for the
        // urn:nfc:sn:handover service
        Assert.assertTrue(NfcUtil.ndefMessageContainsServiceParameterRecord(initialNdefMessage,
                "urn:nfc:sn:handover"));

        // Keep the following code in sync with verificationHelper.startNegotiatedHandover()

        // Select the service, the resulting NDEF message is specified in
        // in Tag NDEF Exchange Protocol Technical Specification Version 1.0
        // section 4.3 TNEP Status Message
        byte[] serviceSelectResponse = ndefTransact(apduRouter, apdusSentByHelper,
                NfcUtil.createNdefMessageServiceSelect("urn:nfc:sn:handover"));
        Assert.assertEquals("d10201546500", Util.toHex(serviceSelectResponse));

        // Now send Handover Request, the resulting NDEF message is Handover Response..
        byte[] hrMessage = NfcUtil.createNdefMessageHandoverRequest(
                connectionMethods,
                null); // TODO: pass ReaderEngagement message
        byte[] hsMessage = ndefTransact(apduRouter, apdusSentByHelper, hrMessage);

        NfcUtil.ParsedHandoverSelectMessage hs = NfcUtil.parseHandoverSelectMessage(hsMessage);
        Assert.assertNotNull(hs);
        EngagementParser parser = new EngagementParser(hs.encodedDeviceEngagement);
        // Check the returned DeviceEngagement
        EngagementParser.Engagement e = parser.parse();
        Assert.assertEquals("1.0", e.getVersion());
        Assert.assertEquals(0, e.getOriginInfos().size());
        Assert.assertEquals(session.getEphemeralKeyPair().getPublic(), e.getESenderKey());
        Assert.assertEquals(0, e.getConnectionMethods().size());

        // Check the synthesized ConnectionMethod (from returned OOB data in HS)... we expect
        // only one to be returned and we expect it to be the BLE one and only the Central
        // Client mode.
        Assert.assertEquals(1, hs.connectionMethods.size());
        ConnectionMethodBle cm = (ConnectionMethodBle) hs.connectionMethods.get(0);
        Assert.assertFalse(cm.getSupportsPeripheralServerMode());
        Assert.assertTrue(cm.getSupportsCentralClientMode());
        Assert.assertNull(cm.getPeripheralServerModeUuid());
        Assert.assertEquals(cm.getCentralClientModeUuid(), bleUuid);

        // Checks that the helper returns the correct DE and Handover
        byte[] expectedHandover = Util.cborEncode(new CborBuilder()
                .addArray()
                .add(hsMessage)      // Handover Select message
                .add(hrMessage)      // Handover Request message
                .end()
                .build().get(0));
        Assert.assertArrayEquals(expectedHandover, helper.getHandover());
        Assert.assertArrayEquals(hs.encodedDeviceEngagement, helper.getDeviceEngagement());

        helper.close();
    }

    static byte[] ndefTransact(NfcApduRouter apduRouter,
                               BlockingQueue<byte[]> apdusSentByHelper,
                               byte[] ndefMessage) throws InterruptedException {
        byte[] responseApdu;

        // First command is UPDATE_BINARY to reset length
        apduRouter.addReceivedApdu(NfcApduRouter.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION,
                NfcUtil.createApduUpdateBinary(0, new byte[] {0x00, 0x00}));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        Assert.assertEquals(NfcUtil.STATUS_WORD_OK, responseApdu);

        // Second command is UPDATE_BINARY with payload, starting at offset 2.
        apduRouter.addReceivedApdu(NfcApduRouter.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION,
                NfcUtil.createApduUpdateBinary(2, ndefMessage));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        Assert.assertEquals(NfcUtil.STATUS_WORD_OK, responseApdu);

        // Third command is UPDATE_BINARY to write the length
        byte[] encodedLength = new byte[] {
                (byte) ((ndefMessage.length / 0x100) & 0xff),
                (byte) (ndefMessage.length & 0xff)};
        apduRouter.addReceivedApdu(NfcApduRouter.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION,
                NfcUtil.createApduUpdateBinary(0, encodedLength));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        Assert.assertEquals(NfcUtil.STATUS_WORD_OK, responseApdu);

        // Finally, read the NDEF response message.. first get the length
        apduRouter.addReceivedApdu(NfcApduRouter.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION,
                NfcUtil.createApduReadBinary(0, 2));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
        // don't know the length.
        Assert.assertEquals(4, responseApdu.length);
        Assert.assertEquals(0x90, responseApdu[2] & 0xff);
        Assert.assertEquals(0x00, responseApdu[3] & 0xff);
        int ndefMessageSize = (((int) responseApdu[0]) & 0xff) * 0x0100
                + (((int) responseApdu[1]) & 0xff);

        // Read NDEF message
        apduRouter.addReceivedApdu(NfcApduRouter.AID_FOR_TYPE_4_TAG_NDEF_APPLICATION,
                NfcUtil.createApduReadBinary(2, ndefMessageSize));
        responseApdu = apdusSentByHelper.poll(5000, TimeUnit.MILLISECONDS);
        Assert.assertNotNull(responseApdu);
        // The response contains the length as 2 bytes followed by STATUS_WORD_OK. Assume we
        // don't know the length.
        Assert.assertEquals(ndefMessageSize + 2, responseApdu.length);
        Assert.assertEquals(0x90, responseApdu[ndefMessageSize] & 0xff);
        Assert.assertEquals(0x00, responseApdu[ndefMessageSize + 1] & 0xff);
        return Arrays.copyOf(responseApdu, responseApdu.length - 2);
    }

}