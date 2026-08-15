package pl.snowdog.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskSecurityTest {
    @Test
    fun `normalizes only a bare HTTPS origin`() {
        assertEquals(
            "https://gast.example.com",
            KioskSecurity.normalizeHttpsOrigin("https://GAST.example.com/"),
        )
        assertNull(KioskSecurity.normalizeHttpsOrigin("http://gast.example.com"))
        assertNull(KioskSecurity.normalizeHttpsOrigin("https://gast.example.com/kiosk"))
        assertNull(KioskSecurity.normalizeHttpsOrigin("https://user@example.com.de"))
        assertNull(KioskSecurity.normalizeHttpsOrigin(null))
        assertNull(KioskSecurity.normalizeHttpsOrigin("  "))
    }

    @Test
    fun `compares scheme host and effective port exactly`() {
        assertTrue(
            KioskSecurity.isAllowedUrl(
                "https://gast.example.com/kiosk?mode=scan",
                "https://gast.example.com",
            ),
        )
        assertFalse(
            KioskSecurity.isAllowedUrl(
                "https://gast.example.com.evil.example/kiosk",
                "https://gast.example.com",
            ),
        )
        assertFalse(
            KioskSecurity.isAllowedUrl(
                "http://gast.example.com/kiosk",
                "https://gast.example.com",
            ),
        )
    }

    @Test
    fun `accepts only UUID print job ids`() {
        assertTrue(KioskSecurity.isValidJobId("123e4567-e89b-42d3-a456-426614174000"))
        assertFalse(KioskSecurity.isValidJobId("../../printer/10.0.0.1"))
        assertFalse(KioskSecurity.isValidJobId("123e4567-e89b-12d3-a456-426614174000/extra"))
    }

    @Test
    fun `validates printer destinations from native settings`() {
        assertTrue(KioskSecurity.isValidPrinterHost("192.168.10.25"))
        assertTrue(KioskSecurity.isValidPrinterHost("badge-printer.local"))
        assertFalse(KioskSecurity.isValidPrinterHost("999.168.10.25"))
        assertFalse(KioskSecurity.isValidPrinterHost("https://192.168.10.25"))
        assertTrue(KioskSecurity.isValidPrinterPort(9100))
        assertFalse(KioskSecurity.isValidPrinterPort(0))
    }
}
