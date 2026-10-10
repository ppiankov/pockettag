package dev.ppiankov.pockettag

import dev.ppiankov.pockettag.Type4Constants.hex
import java.util.Locale
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// WO-9: fixed coordinates exercise the chosen URL encoding without location access.
class PlaceItemTest {
    // WO-26: every supported Maps coordinate form accepts uppercase or mixed-case web schemes.
    @Test
    fun uppercaseAndMixedCaseSchemesKeepMapsCoordinates() {
        listOf("HTTPS://www.google.com/maps?q=48.85837,2.294481",
            "Http://maps.google.com/?query=48.85837%2C2.294481",
            "HTTPS://www.google.com/maps/place/Example/@48.85837,2.294481,17z")
            .forEach { link ->
                assertEquals(48.85837 to 2.294481, TagItem.Place.coordinatesFromLink(link))
            }
    }

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
                // WO-24: the existing short-link fixture now receives the specified guidance.
                assertEquals(if (link == "https://maps.app.goo.gl/example") SHORT_LINK_GUIDANCE
                    else "Could not read coordinates from this link", error.message)
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

    // WO-24: full Maps links using the current API parameter remain usable as input.
    @Test
    fun apiQueryCoordinatesAreReadFromSupportedMapsHosts() {
        listOf("google.com", "www.google.com", "maps.google.com").forEach { host ->
            assertEquals(48.85837 to 2.294481, TagItem.Place.coordinatesFromLink(
                "https://$host/maps/search/?api=1&query=48.85837%2C2.294481"))
        }
    }

    // WO-24: a place's own served link can be pasted back without changing its coordinates.
    @Test
    fun servedPlaceUrisRoundTripThroughTheLinkParser() {
        listOf(48.85837 to 2.294481, 0.00001 to -0.00001, -90.0 to 180.0).forEach { (lat, lng) ->
            val place = TagItem.Place("Place", lat, lng)
            assertEquals(lat to lng, TagItem.Place.coordinatesFromLink(place.uri))
        }
    }

    // WO-24: both known short-link hosts explain why coordinates cannot be read offline.
    @Test
    fun shortMapLinksKeepTheirSpecificGuidance() {
        listOf("https://maps.app.goo.gl/example", "http://maps.app.goo.gl/example?other=value",
            "https://goo.gl/maps", "https://goo.gl/maps/example").forEach { link ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                TagItem.Place.coordinatesFromLink(link)
            }
            assertEquals(SHORT_LINK_GUIDANCE, error.message)
        }
    }

    // WO-24: the editor's shared formatter stays plain decimal regardless of the phone locale.
    @Test
    fun editorCoordinateFormatAvoidsScientificNotationAndLocaleCommas() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("0.00001", TagItem.Place.coordinate(0.00001))
            assertEquals("-0.00001", TagItem.Place.coordinate(-0.00001))
            assertEquals("3.5", TagItem.Place.coordinate(3.5))
            assertEquals("0", TagItem.Place.coordinate(-0.0000001))
        } finally {
            Locale.setDefault(before)
        }
    }

    // WO-24: pasted comma decimals and existing dot decimals produce the same coordinate.
    @Test
    fun coordinateInputAcceptsASingleDecimalComma() {
        assertEquals(3.5, requireNotNull(TagItem.Place.coordinateFromInput("3,5")), 0.0)
        assertEquals(-3.5, requireNotNull(TagItem.Place.coordinateFromInput(" -3,5 ")), 0.0)
        assertEquals(3.5, requireNotNull(TagItem.Place.coordinateFromInput("3.5")), 0.0)
    }

    // WO-24: mixed or repeated separators cannot silently become a different coordinate.
    @Test
    fun coordinateInputRejectsAmbiguousDecimalSeparators() {
        listOf("3,5,1", "3.5,1", "3,5.1", "3,,5", "", "not a number").forEach { input ->
            assertNull(TagItem.Place.coordinateFromInput(input))
        }
    }

    private companion object {
        // WO-24: pin the complete user-facing short-link message independently of production constants.
        const val SHORT_LINK_GUIDANCE = "Short links need the internet to open. In Google Maps, open the place, then copy the coordinates or the full link from your browser."
    }
}
