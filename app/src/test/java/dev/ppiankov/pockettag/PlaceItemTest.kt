package dev.ppiankov.pockettag

import dev.ppiankov.pockettag.Type4Constants.hex
import java.util.Locale
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// WO-9: fixed coordinates exercise the chosen URL encoding without location access.
class PlaceItemTest {
    @Test
    fun landmarkHasExactGoogleMapsUriRecord() {
        val place = TagItem.Place("Meeting point", 48.85837, 2.294481, "Example landmark")
        assertEquals("place", place.type.storageName)
        assertEquals("https://www.google.com/maps/search/?api=1&query=48.85837,2.294481", place.uri)
        assertArrayEquals(hex("D101365502676F6F676C652E636F6D2F6D6170732F7365617263682F" +
            "3F6170693D312671756572793D34382E38353833372C322E323934343831"), place.ndefMessage())
    }

    @Test
    fun negativeCoordinatesAndTrailingZerosAreFormattedWithoutALocaleComma() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("https://www.google.com/maps/search/?api=1&query=-33.8688,151.2093",
                TagItem.Place("Place", -33.8688, 151.2093).uri)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun coordinatesRoundToAtMostSixDecimals() {
        assertEquals("https://www.google.com/maps/search/?api=1&query=1.123457,-2.765433",
            TagItem.Place("Place", 1.1234567, -2.7654328).uri)
        assertEquals("https://www.google.com/maps/search/?api=1&query=0,0",
            TagItem.Place("Place", -0.0000001, 0.0).uri)
    }

    @Test
    fun atCoordinatesAreReadFromAGoogleMapsPath() {
        assertEquals(48.85837 to 2.294481, TagItem.Place.coordinatesFromLink(
            " https://www.google.com/maps/place/Example/@48.85837,2.294481,17z "))
    }

    @Test
    fun queryCoordinatesAreReadFromBothMapsHosts() {
        assertEquals(-33.8688 to 151.2093, TagItem.Place.coordinatesFromLink(
            "https://maps.google.com/?q=-33.8688,151.2093"))
        assertEquals(48.85837 to 2.294481, TagItem.Place.coordinatesFromLink(
            "https://www.google.com/maps?q=48.85837%2C2.294481&other=value"))
    }

    @Test
    fun unsupportedAndMalformedLinksUseTheSpecifiedError() {
        listOf("https://example.com/maps/@1,2", "https://www.google.com/search?q=1,2",
            "https://maps.app.goo.gl/example", "geo:1,2", "not a link",
            "https://www.google.com/maps?q=a,b", "https://www.google.com/maps?q=1,2junk",
            "https://www.google.com/maps?q=%GG", "https://www.google.com/maps/@91,2,17z")
            .forEach { link ->
                val error = assertThrows(IllegalArgumentException::class.java) {
                    TagItem.Place.coordinatesFromLink(link)
                }
                assertEquals("Could not read coordinates from this link", error.message)
            }
    }

    @Test
    fun coordinatesAcceptBoundsAndRejectOutOfRangeOrNonFiniteValues() {
        TagItem.Place("North", 90.0, 180.0)
        TagItem.Place("South", -90.0, -180.0)
        listOf(90.000001 to 0.0, -90.000001 to 0.0, 0.0 to 180.000001, 0.0 to -180.000001,
            Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY).forEach { (latitude, longitude) ->
            assertThrows(IllegalArgumentException::class.java) { TagItem.Place("Place", latitude, longitude) }
        }
    }

    @Test
    fun jsonRoundTripKeepsCoordinatesNameAndIdentity() {
        val place = TagItem.Place("Meeting point", 48.85837, 2.294481, "Example landmark", "place-id")
        val json = ItemJson.encode(listOf(place))
        val restored = ItemJson.decode(json).items.single() as TagItem.Place
        assertEquals(place.id, restored.id)
        assertEquals(place.label, restored.label)
        assertEquals(place.latitude, restored.latitude, 0.0)
        assertEquals(place.longitude, restored.longitude, 0.0)
        assertEquals(place.name, restored.name)
        assertArrayEquals(place.ndefMessage(), restored.ndefMessage())
        assertEquals(json, ItemJson.encode(listOf(restored)))
    }

    @Test
    fun wrongStoredCoordinateTypesRemainOpaque() {
        val json = """[{"id":"bad-place","type":"place","label":"Place","latitude":"1","longitude":2,"name":""}]"""
        val document = ItemJson.decode(json)
        assertTrue(document.readable)
        assertTrue(document.items.isEmpty())
        assertEquals(json, ItemJson.encode(document))
    }

    @Test
    fun preClusterJsonStillLoadsUnchanged() {
        val state = ItemState.load(PRE_CLUSTER_JSON, "old-link", null)
        assertTrue(state.readable)
        assertEquals(listOf("link", "contact", "whatsapp", "call", "email", "sms", "raw"),
            state.items.map { it.type.storageName })
        assertEquals("old-link", state.activeItemId)
        assertEquals("https://example.com/", state.activeItem?.uri)
        assertTrue(JSONArray(PRE_CLUSTER_JSON).similar(JSONArray(ItemJson.encode(state.document))))
    }
}
