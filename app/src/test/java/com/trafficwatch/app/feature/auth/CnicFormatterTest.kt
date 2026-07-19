package com.trafficwatch.app.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class CnicFormatterTest {

    @Test
    fun `formats complete 13-digit cnic with dashes after 5th and 12th digit`() {
        assertEquals("12345-1234567-1", formatCnicWithDashes("1234512345671"))
    }

    @Test
    fun `formats partial input without a trailing dash`() {
        assertEquals("1234", formatCnicWithDashes("1234"))
        assertEquals("12345", formatCnicWithDashes("12345"))
        assertEquals("12345-6", formatCnicWithDashes("123456"))
        assertEquals("12345-1234567", formatCnicWithDashes("123451234567"))
    }

    @Test
    fun `formats empty input as empty string`() {
        assertEquals("", formatCnicWithDashes(""))
    }
}
