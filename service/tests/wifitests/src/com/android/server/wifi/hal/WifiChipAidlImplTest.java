/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wifi.hal;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.wifi.TestUtil.createCapabilityBitset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.wifi.AfcChannelAllowance;
import android.hardware.wifi.IWifiChip;
import android.hardware.wifi.IWifiChip.FeatureSetMask;
import android.hardware.wifi.IWifiChip.VoipMode;
import android.hardware.wifi.WifiDebugHostWakeReasonRxIcmpPacketDetails;
import android.hardware.wifi.WifiDebugHostWakeReasonRxMulticastPacketDetails;
import android.hardware.wifi.WifiDebugHostWakeReasonRxPacketDetails;
import android.hardware.wifi.WifiDebugHostWakeReasonStats;
import android.hardware.wifi.WifiDebugRingBufferFlags;
import android.hardware.wifi.WifiDebugRingBufferStatus;
import android.hardware.wifi.WifiDebugRingBufferVerboseLevel;
import android.hardware.wifi.WifiStatusCode;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import com.android.server.wifi.SarInfo;
import com.android.server.wifi.SsidTranslator;
import com.android.server.wifi.WifiBaseTest;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.WlanWakeReasonAndCounts;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

public class WifiChipAidlImplTest extends WifiBaseTest {
    private WifiChipAidlImpl mDut;
    private MockitoSession mSession;

    @Mock private IWifiChip mIWifiChipMock;
    @Mock private Context mContextMock;
    @Mock private SsidTranslator mSsidTranslatorMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDut = new WifiChipAidlImpl(mIWifiChipMock, mContextMock, mSsidTranslatorMock);
        mSession = mockitoSession()
                .mockStatic(WifiHalAidlImpl.class)
                .startMocking();
    }

    @After
    public void tearDown() {
        mSession.finishMocking();
    }

    @Test
    public void testGetIdSuccess() throws Exception {
        final int id = 7;
        when(mIWifiChipMock.getId()).thenReturn(id);
        assertEquals(id, mDut.getId());
        verify(mIWifiChipMock).getId();
    }

    @Test
    public void testGetIdRemoteException() throws Exception {
        doThrow(new RemoteException()).when(mIWifiChipMock).getId();
        assertEquals(-1, mDut.getId());
        verify(mIWifiChipMock).getId();
    }

    @Test
    public void testGetIdServiceSpecificException() throws Exception {
        doThrow(new ServiceSpecificException(WifiStatusCode.ERROR_UNKNOWN))
                .when(mIWifiChipMock).getId();
        assertEquals(-1, mDut.getId());
        verify(mIWifiChipMock).getId();
    }

    @Test
    public void testGetUsableChannelsNotSupportedIsCached() throws Exception {
        doThrow(new ServiceSpecificException(WifiStatusCode.ERROR_NOT_SUPPORTED))
                .when(mIWifiChipMock).getUsableChannels(anyInt(), anyInt(), anyInt());

        assertNull(mDut.getUsableChannels(
                WifiScanner.WIFI_BAND_24_GHZ,
                WifiAvailableChannel.OP_MODE_STA,
                WifiAvailableChannel.FILTER_REGULATORY));
        assertNull(mDut.getUsableChannels(
                WifiScanner.WIFI_BAND_24_GHZ,
                WifiAvailableChannel.OP_MODE_STA,
                WifiAvailableChannel.FILTER_REGULATORY));

        verify(mIWifiChipMock, times(1))
                .getUsableChannels(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testGetUsableChannelsTransientErrorIsNotCached() throws Exception {
        doThrow(new ServiceSpecificException(WifiStatusCode.ERROR_BUSY))
                .when(mIWifiChipMock).getUsableChannels(anyInt(), anyInt(), anyInt());

        assertNull(mDut.getUsableChannels(
                WifiScanner.WIFI_BAND_24_GHZ,
                WifiAvailableChannel.OP_MODE_STA,
                WifiAvailableChannel.FILTER_REGULATORY));
        assertNull(mDut.getUsableChannels(
                WifiScanner.WIFI_BAND_24_GHZ,
                WifiAvailableChannel.OP_MODE_STA,
                WifiAvailableChannel.FILTER_REGULATORY));

        verify(mIWifiChipMock, times(2))
                .getUsableChannels(anyInt(), anyInt(), anyInt());
    }

    /**
     * Test translation to WifiManager.WIFI_FEATURE_*
     */
    @Test
    public void testChipFeatureMaskTranslation() {
        int halFeatures = (
                android.hardware.wifi.IWifiChip.FeatureSetMask.SET_TX_POWER_LIMIT
                        | android.hardware.wifi.IWifiChip.FeatureSetMask.D2D_RTT
                        | android.hardware.wifi.IWifiChip.FeatureSetMask.D2AP_RTT
        );
        BitSet expected = createCapabilityBitset(WifiManager.WIFI_FEATURE_TX_POWER_LIMIT,
                WifiManager.WIFI_FEATURE_D2D_RTT, WifiManager.WIFI_FEATURE_D2AP_RTT);
        assertTrue(expected.equals(mDut.halToFrameworkChipFeatureSet(halFeatures)));
    }

    /**
     * Test that getRingBufferStatus gets and translates its values correctly.
     */
    @Test
    public void testRingBufferStatus() throws Exception {
        WifiDebugRingBufferStatus one = new WifiDebugRingBufferStatus();
        one.ringName = "One";
        one.flags = WifiDebugRingBufferFlags.HAS_BINARY_ENTRIES;
        one.ringId = 5607371;
        one.sizeInBytes = 54321;
        one.freeSizeInBytes = 42;
        one.verboseLevel = WifiDebugRingBufferVerboseLevel.VERBOSE;
        String oneExpect = "name: One flag: 1 ringBufferId: 5607371 ringBufferByteSize: 54321"
                + " verboseLevel: 2 writtenBytes: 0 readBytes: 0 writtenRecords: 0";

        WifiDebugRingBufferStatus two = new WifiDebugRingBufferStatus();
        two.ringName = "Two";
        two.flags = WifiDebugRingBufferFlags.HAS_ASCII_ENTRIES
                | WifiDebugRingBufferFlags.HAS_PER_PACKET_ENTRIES;
        two.ringId = 4512470;
        two.sizeInBytes = 300;
        two.freeSizeInBytes = 42;
        two.verboseLevel = WifiDebugRingBufferVerboseLevel.DEFAULT;

        WifiDebugRingBufferStatus[] halBufferStatus = new WifiDebugRingBufferStatus[]{one, two};
        when(mIWifiChipMock.getDebugRingBuffersStatus()).thenReturn(halBufferStatus);

        List<WifiNative.RingBufferStatus> actual = mDut.getDebugRingBuffersStatus();
        assertEquals(halBufferStatus.length, actual.size());
        assertEquals(oneExpect, actual.get(0).toString());
        assertEquals(two.ringId, actual.get(1).ringBufferId);
    }

    /**
     * Tests the retrieval of wlan wake reason stats.
     */
    @Test
    public void testGetWlanWakeReasonCount() throws Exception {
        WifiDebugHostWakeReasonStats stats = new WifiDebugHostWakeReasonStats();
        Random rand = new Random();
        stats.totalCmdEventWakeCnt = rand.nextInt();
        stats.totalDriverFwLocalWakeCnt = rand.nextInt();
        stats.totalRxPacketWakeCnt = rand.nextInt();
        stats.rxPktWakeDetails = new WifiDebugHostWakeReasonRxPacketDetails();
        stats.rxPktWakeDetails.rxUnicastCnt = rand.nextInt();
        stats.rxPktWakeDetails.rxMulticastCnt = rand.nextInt();
        stats.rxIcmpPkWakeDetails = new WifiDebugHostWakeReasonRxIcmpPacketDetails();
        stats.rxIcmpPkWakeDetails.icmpPkt = rand.nextInt();
        stats.rxIcmpPkWakeDetails.icmp6Pkt = rand.nextInt();
        stats.rxMulticastPkWakeDetails = new WifiDebugHostWakeReasonRxMulticastPacketDetails();
        stats.rxMulticastPkWakeDetails.ipv4RxMulticastAddrCnt = rand.nextInt();
        stats.rxMulticastPkWakeDetails.ipv6RxMulticastAddrCnt = rand.nextInt();
        when(mIWifiChipMock.getDebugHostWakeReasonStats()).thenReturn(stats);

        WlanWakeReasonAndCounts retrievedStats = mDut.getDebugHostWakeReasonStats();
        verify(mIWifiChipMock).getDebugHostWakeReasonStats();

        assertNotNull(retrievedStats);
        assertEquals(stats.totalCmdEventWakeCnt, retrievedStats.totalCmdEventWake);
        assertEquals(stats.totalDriverFwLocalWakeCnt, retrievedStats.totalDriverFwLocalWake);
        assertEquals(stats.totalRxPacketWakeCnt, retrievedStats.totalRxDataWake);
        assertEquals(stats.rxPktWakeDetails.rxUnicastCnt, retrievedStats.rxUnicast);
        assertEquals(stats.rxPktWakeDetails.rxMulticastCnt, retrievedStats.rxMulticast);
        assertEquals(stats.rxIcmpPkWakeDetails.icmpPkt, retrievedStats.icmp);
        assertEquals(stats.rxIcmpPkWakeDetails.icmp6Pkt, retrievedStats.icmp6);
        assertEquals(stats.rxMulticastPkWakeDetails.ipv4RxMulticastAddrCnt,
                retrievedStats.ipv4RxMulticast);
        assertEquals(stats.rxMulticastPkWakeDetails.ipv6RxMulticastAddrCnt,
                retrievedStats.ipv6Multicast);
    }

    @Test
    public void testSelectTxPowerScenario() throws RemoteException {
        // Create a SAR info record
        SarInfo sarInfo = new SarInfo();
        sarInfo.sarVoiceCallSupported = true;
        sarInfo.sarSapSupported = false;
        sarInfo.isVoiceCall = true;

        assertTrue(mDut.selectTxPowerScenario(sarInfo));
        verify(mIWifiChipMock).selectTxPowerScenario(
                eq(android.hardware.wifi.IWifiChip.TxPowerScenario.VOICE_CALL));
        verify(mIWifiChipMock, never()).resetTxPowerScenario();
    }

    /**
     * Test the selectTxPowerScenario method with SAP and voice call support.
     * When SAP is enabled, should result in SAP with near-body scenario.
     */
    @Test
    public void testSelectTxPowerScenario_SapScenarios() throws RemoteException {
        // Create a SAR info record (with SAP support)
        SarInfo sarInfo = new SarInfo();
        sarInfo.sarVoiceCallSupported = true;
        sarInfo.sarSapSupported = true;
        sarInfo.isWifiSapEnabled = true;

        assertTrue(mDut.selectTxPowerScenario(sarInfo));
        verify(mIWifiChipMock).selectTxPowerScenario(
                eq(android.hardware.wifi.IWifiChip.TxPowerScenario.ON_BODY_CELL_ON));
        verify(mIWifiChipMock, never()).resetTxPowerScenario();
    }

    /**
     * Test the selectTxPowerScenario method with SAP and voice call support.
     * When a voice call is ongoing, should result in cell with near-head scenario.
     */
    @Test
    public void testSelectTxPowerScenario_VoiceCallScenarios() throws RemoteException {
        // Create a SAR info record (with SAP support)
        SarInfo sarInfo = new SarInfo();
        sarInfo.sarVoiceCallSupported = true;
        sarInfo.sarSapSupported = true;
        sarInfo.isVoiceCall = true;

        assertTrue(mDut.selectTxPowerScenario(sarInfo));
        verify(mIWifiChipMock).selectTxPowerScenario(
                eq(android.hardware.wifi.IWifiChip.TxPowerScenario.ON_HEAD_CELL_ON));
        verify(mIWifiChipMock, never()).resetTxPowerScenario();
    }

    /**
     * Test the selectTxPowerScenario method with SAP and voice call support.
     * When earpiece is active, should result in cell with near-head scenario.
     */
    @Test
    public void testSelectTxPowerScenario_EarPieceScenarios() throws RemoteException {
        // Create a SAR info record (with SAP support)
        SarInfo sarInfo = new SarInfo();
        sarInfo.sarVoiceCallSupported = true;
        sarInfo.sarSapSupported = true;
        sarInfo.isEarPieceActive = true;

        assertTrue(mDut.selectTxPowerScenario(sarInfo));
        verify(mIWifiChipMock).selectTxPowerScenario(
                eq(android.hardware.wifi.IWifiChip.TxPowerScenario.ON_HEAD_CELL_ON));
        verify(mIWifiChipMock, never()).resetTxPowerScenario();
    }

    /**
     * Test the selectTxPowerScenario method with SAP and voice call support.
     * When SAP is enabled and a voice call is ongoing, should result in
     * cell with near-head scenario.
     */
    @Test
    public void testSelectTxPowerScenario_head_sap_call() throws RemoteException {
        // Create a SAR info record (with SAP support)
        SarInfo sarInfo = new SarInfo();
        sarInfo.sarVoiceCallSupported = true;
        sarInfo.sarSapSupported = true;
        sarInfo.isWifiSapEnabled = true;
        sarInfo.isVoiceCall = true;

        assertTrue(mDut.selectTxPowerScenario(sarInfo));
        verify(mIWifiChipMock).selectTxPowerScenario(
                eq(android.hardware.wifi.IWifiChip.TxPowerScenario.ON_HEAD_CELL_ON));
        verify(mIWifiChipMock, never()).resetTxPowerScenario();
    }

    @Test
    public void testResetTxPowerScenario() throws RemoteException {
        // Create a SAR info record (no SAP support)
        SarInfo sarInfo = new SarInfo();
        sarInfo.sarVoiceCallSupported = true;
        sarInfo.sarSapSupported = false;

        assertTrue(mDut.selectTxPowerScenario(sarInfo));
        verify(mIWifiChipMock).resetTxPowerScenario();
        verify(mIWifiChipMock, never()).selectTxPowerScenario(anyInt());
    }

    /**
     * Test setting SAR scenario when not needed.
     * Should return true without invoking the HAL method.
     */
    @Test
    public void testSetTxPowerScenarioNotNeeded() throws RemoteException {
        InOrder inOrder = inOrder(mIWifiChipMock);

        // Create a SAR info record (no SAP support)
        SarInfo sarInfo = new SarInfo();
        sarInfo.sarVoiceCallSupported = true;
        sarInfo.sarSapSupported = true;

        // Calling set once
        assertTrue(mDut.selectTxPowerScenario(sarInfo));
        inOrder.verify(mIWifiChipMock).resetTxPowerScenario();
        sarInfo.reportingSuccessful();

        // Calling set a second time
        assertTrue(mDut.selectTxPowerScenario(sarInfo));
        inOrder.verify(mIWifiChipMock, never()).resetTxPowerScenario();
        inOrder.verify(mIWifiChipMock, never()).selectTxPowerScenario(anyInt());
    }

    /**
     * Test resetting SAR scenario when not needed.
     * Should return true without invoking the HAL method.
     */
    @Test
    public void testResetTxPowerScenarioNotNeeded() throws RemoteException {
        InOrder inOrder = inOrder(mIWifiChipMock);

        // Create a SAR info record (no SAP support)
        SarInfo sarInfo = new SarInfo();
        sarInfo.sarVoiceCallSupported = true;
        sarInfo.sarSapSupported = false;

        // Calling reset once
        assertTrue(mDut.selectTxPowerScenario(sarInfo));
        inOrder.verify(mIWifiChipMock).resetTxPowerScenario();
        inOrder.verify(mIWifiChipMock, never()).selectTxPowerScenario(anyInt());
        sarInfo.reportingSuccessful();

        // Calling reset a second time
        assertTrue(mDut.selectTxPowerScenario(sarInfo));
        inOrder.verify(mIWifiChipMock, never()).resetTxPowerScenario();
        inOrder.verify(mIWifiChipMock, never()).selectTxPowerScenario(anyInt());
    }

    @Test
    public void testSetMultiStaUseCase() throws Exception {
        assertTrue(mDut.setMultiStaUseCase(WifiNative.DUAL_STA_TRANSIENT_PREFER_PRIMARY));
        verify(mIWifiChipMock).setMultiStaUseCase(
                android.hardware.wifi.IWifiChip.MultiStaUseCase.DUAL_STA_TRANSIENT_PREFER_PRIMARY);

        assertTrue(mDut.setMultiStaUseCase(WifiNative.DUAL_STA_NON_TRANSIENT_UNBIASED));
        verify(mIWifiChipMock).setMultiStaUseCase(
                android.hardware.wifi.IWifiChip.MultiStaUseCase.DUAL_STA_NON_TRANSIENT_UNBIASED);

        // Illegal value
        assertFalse(mDut.setMultiStaUseCase(5));
    }

    @Test
    public void testEnableStaChannelForPeerNetwork() throws Exception {
        assertTrue(mDut.enableStaChannelForPeerNetwork(true, true));
        int channelCategoryEnableFlag = IWifiChip.ChannelCategoryMask.INDOOR_CHANNEL
                | IWifiChip.ChannelCategoryMask.DFS_CHANNEL;
        verify(mIWifiChipMock).enableStaChannelForPeerNetwork(eq(channelCategoryEnableFlag));
    }

    /**
     * Verifies that setAfcChannelAllowance() calls underlying WifiVendorHAL, and that the
     * AfcChannelAllowance is correctly converted from the framework version to the HAL version.
     */
    @Test
    public void testSetAfcChannelAllowance() throws RemoteException {
        WifiChip.AfcChannelAllowance afcChannelAllowance = makeAfcChannelAllowance();
        mDut.setAfcChannelAllowance(afcChannelAllowance);

        ArgumentCaptor<AfcChannelAllowance> afcChannelAllowanceCaptor = ArgumentCaptor.forClass(
                AfcChannelAllowance.class);
        verify(mIWifiChipMock).setAfcChannelAllowance(afcChannelAllowanceCaptor.capture());
        AfcChannelAllowance halAfcChannelAllowance = afcChannelAllowanceCaptor.getValue();

        // Verify that the AFC allowance object was correctly converted from the framework version
        // to the HAL version.
        assertEquals(halAfcChannelAllowance.availableAfcFrequencyInfos.length,
                afcChannelAllowance.availableAfcFrequencyInfos.size());
        assertEquals(halAfcChannelAllowance.availableAfcChannelInfos.length,
                afcChannelAllowance.availableAfcChannelInfos.size());
        assertEquals(halAfcChannelAllowance.availabilityExpireTimeMs,
                afcChannelAllowance.availabilityExpireTimeMs);

        for (int i = 0; i < halAfcChannelAllowance.availableAfcFrequencyInfos.length; ++i) {
            assertEquals(halAfcChannelAllowance.availableAfcFrequencyInfos[i].startFrequencyMhz,
                    afcChannelAllowance.availableAfcFrequencyInfos.get(i).startFrequencyMhz);
            assertEquals(halAfcChannelAllowance.availableAfcFrequencyInfos[i].endFrequencyMhz,
                    afcChannelAllowance.availableAfcFrequencyInfos.get(i).endFrequencyMhz);
            assertEquals(halAfcChannelAllowance.availableAfcFrequencyInfos[i].maxPsd,
                    afcChannelAllowance.availableAfcFrequencyInfos.get(i).maxPsdDbmPerMhz);
        }

        for (int i = 0; i < halAfcChannelAllowance.availableAfcChannelInfos.length; ++i) {
            assertEquals(halAfcChannelAllowance.availableAfcChannelInfos[i].globalOperatingClass,
                    afcChannelAllowance.availableAfcChannelInfos.get(i).globalOperatingClass);
            assertEquals(halAfcChannelAllowance.availableAfcChannelInfos[i].channelCfi,
                    afcChannelAllowance.availableAfcChannelInfos.get(i).channelCfi);
            assertEquals(halAfcChannelAllowance.availableAfcChannelInfos[i].maxEirpDbm,
                    afcChannelAllowance.availableAfcChannelInfos.get(i).maxEirpDbm);
        }
    }

    /**
     * Creates and returns an object used to set the allowed AFC channels and frequencies.
     */
    WifiChip.AfcChannelAllowance makeAfcChannelAllowance() {
        WifiChip.AfcChannelAllowance afcChannelAllowance = new WifiChip.AfcChannelAllowance();

        int[] startFrequencies = {5995, 6150, 6350};
        int[] endFrequencies = {6110, 6300, 6590};
        int[] maxPsds = {13, 27, 14};

        afcChannelAllowance.availableAfcFrequencyInfos = new ArrayList<>();
        afcChannelAllowance.availableAfcChannelInfos = new ArrayList<>();
        afcChannelAllowance.availabilityExpireTimeMs = 1000L;

        for (int i = 0; i < startFrequencies.length; ++i) {
            afcChannelAllowance.availableAfcFrequencyInfos.add(
                    new WifiChip.AvailableAfcFrequencyInfo());
            afcChannelAllowance.availableAfcFrequencyInfos.get(i).startFrequencyMhz =
                    startFrequencies[i];
            afcChannelAllowance.availableAfcFrequencyInfos.get(i).endFrequencyMhz =
                    endFrequencies[i];
            afcChannelAllowance.availableAfcFrequencyInfos.get(i).maxPsdDbmPerMhz = maxPsds[i];
        }

        int[] globalOperatingClasses = {3, 5, 17};
        int[] channelCfis = {20, 25, 35};
        int[] maxEirpDbms = {100, 200, 950};

        for (int i = 0; i < globalOperatingClasses.length; ++i) {
            afcChannelAllowance.availableAfcChannelInfos.add(
                    new WifiChip.AvailableAfcChannelInfo());
            afcChannelAllowance.availableAfcChannelInfos.get(i).globalOperatingClass =
                    globalOperatingClasses[i];
            afcChannelAllowance.availableAfcChannelInfos.get(i).channelCfi = channelCfis[i];
            afcChannelAllowance.availableAfcChannelInfos.get(i).maxEirpDbm = maxEirpDbms[i];
        }

        return afcChannelAllowance;
    }

    /**
     * Test that setVoipMode() results in calling into WifiChip
     */
    @Test
    public void testVoipMode() throws Exception {
        // Old Hal should not execute the call.
        when(mIWifiChipMock.getFeatureSet()).thenReturn(FeatureSetMask.SET_VOIP_MODE);
        lenient().when(WifiHalAidlImpl.isServiceVersionAtLeast(2))
                .thenReturn(false);
        // Update feature set to support VOIP.
        mDut.getCapabilitiesAfterIfacesExist();
        // Old Hal should not execute the call.
        assertFalse(mDut.setVoipMode(VoipMode.VOICE));
        assertFalse(mDut.setVoipMode(VoipMode.OFF));
        verify(mIWifiChipMock, never()).setVoipMode(anyInt());
        // Call HAL when hal is V2 or above.
        lenient().when(WifiHalAidlImpl.isServiceVersionAtLeast(2))
                .thenReturn(true);
        assertTrue(mDut.setVoipMode(WifiChip.WIFI_VOIP_MODE_VOICE));
        verify(mIWifiChipMock).setVoipMode(VoipMode.VOICE);
        assertTrue(mDut.setVoipMode(VoipMode.OFF));
        // Don't call HAL when feature is not supported
        reset(mIWifiChipMock);
        // Update feature set
        mDut.getCapabilitiesAfterIfacesExist();
        assertFalse(mDut.setVoipMode(VoipMode.VOICE));
        assertFalse(mDut.setVoipMode(VoipMode.OFF));
        verify(mIWifiChipMock, never()).setVoipMode(anyInt());
    }
}
