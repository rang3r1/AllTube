package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.StringReader;

/** The two hand-rolled cast parsers: lounge long-poll chunks and Cast v2 protobuf frames. */
public class CastProtocolTest {
    @Test
    public void loungeChunkSpanningLinesIsParsedOnceComplete() throws Exception {
        // BrowserChannel format: a length line, then one JSON array whose events start on own lines.
        String response = "120\n"
                + "[[0,[\"c\",\"SID1\",\"\",8]]\n"
                + ",[1,[\"S\",\"GS1\"]]\n"
                + "]\n"
                + "70\n"
                + "[[2,[\"onStateChange\",{\"currentTime\":\"12.5\",\"duration\":\"100\",\"state\":\"1\"}]]]\n";

        LoungeCast.parse(new StringReader(response));

        assertTrue(LoungeCast.isConnected());
        assertEquals(LoungeCast.STATE_PLAYING, LoungeCast.state());
        assertEquals(100, LoungeCast.durationSec());
        assertEquals(12, LoungeCast.positionSec());
    }

    @Test
    public void castFrameRoundTripsWithMultiByteLengths() throws Exception {
        // > 127 bytes, so the payload length needs a two-byte varint.
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longText.append('x');
        }
        JSONObject payload = new JSONObject().put("type", "LAUNCH").put("text", longText.toString());

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        TvDiscovery.send(wire, "receiver-0", "urn:x-cast:com.google.cast.receiver", payload);
        String[] message = TvDiscovery.receive(new DataInputStream(new ByteArrayInputStream(wire.toByteArray())));

        assertEquals("sender-0", message[0]);
        assertEquals("urn:x-cast:com.google.cast.receiver", message[1]);
        assertEquals(longText.toString(), new JSONObject(message[2]).getString("text"));
    }

    @Test
    public void dialReplyAndDescriptionAreParsed() {
        // Captured from SmartTube 32.71 on a Chromecast with Google TV.
        String reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n"
                + "location: http://192.168.0.65:8090/dd.xml\r\nST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n";
        String description = "<root><device><deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>\n"
                + "    <friendlyName> Chromecast (SmartTube) </friendlyName>\n<modelName>Chromecast</modelName>";

        assertEquals("http://192.168.0.65:8090/dd.xml", TvDiscovery.header(reply, "LOCATION"));
        assertEquals(null, TvDiscovery.header(reply, "Application-URL"));
        assertEquals("Chromecast (SmartTube)", TvDiscovery.tag(description, "friendlyName"));
    }
}
