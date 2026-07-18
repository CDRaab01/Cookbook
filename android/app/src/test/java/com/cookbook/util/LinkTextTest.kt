package com.cookbook.util

import org.junit.Test
import kotlin.test.assertEquals

/** Mirrors the server's `test_link_items.py` pure tables — the two splits must agree. */
class LinkTextTest {

    private val meijerUrl =
        "https://www.meijer.com/shopping/product/haakaa-ladybug-breast-milk-collectors--2-5-oz--2-pk" +
            "/942006020367.html?gclsrc=aw.ds&&cmpid=PMAX:LIA:20459226622:::Google&gad_source=1"

    @Test
    fun `plain text passes through`() {
        assertEquals("milk" to null, LinkText.splitLink("milk"))
        assertEquals("milk collector" to null, LinkText.splitLink("  milk   collector  "))
    }

    @Test
    fun `url only splits to empty text`() {
        assertEquals("" to "https://example.com/x", LinkText.splitLink("https://example.com/x"))
    }

    @Test
    fun `typed text plus url keeps the text either side`() {
        assertEquals("milk collector" to meijerUrl, LinkText.splitLink("milk collector $meijerUrl"))
        assertEquals("milk collector" to meijerUrl, LinkText.splitLink("$meijerUrl milk collector"))
    }

    @Test
    fun `trailing punctuation comes off the url and orphaned wrappers off the text`() {
        assertEquals("look" to "https://example.com/x", LinkText.splitLink("look (https://example.com/x)"))
        assertEquals("see" to "https://example.com/x", LinkText.splitLink("see https://example.com/x."))
    }

    @Test
    fun `extra urls are dropped from the text`() {
        assertEquals("a b c" to "https://one.com/x", LinkText.splitLink("a https://one.com/x b https://two.com/y c"))
    }

    @Test
    fun `nameFromUrl prefers the human slug and skips id segments`() {
        assertEquals(
            "haakaa ladybug breast milk collectors 2 5 oz 2 pk",
            LinkText.nameFromUrl(meijerUrl),
        )
        assertEquals(
            "Great Value Whole Milk Gallon",
            LinkText.nameFromUrl("https://www.walmart.com/ip/Great-Value-Whole-Milk-Gallon/10450114"),
        )
        assertEquals("olive oil", LinkText.nameFromUrl("https://example.com/products/olive%20oil"))
        assertEquals("example.com", LinkText.nameFromUrl("https://example.com/12345/67890"))
        assertEquals("example.com", LinkText.nameFromUrl("https://www.example.com"))
    }

    @Test
    fun `displayHost strips scheme www port and path`() {
        assertEquals("meijer.com", LinkText.displayHost("https://www.meijer.com/shopping/x?q=1"))
        assertEquals("example.com", LinkText.displayHost("http://example.com:8080/y"))
    }
}
