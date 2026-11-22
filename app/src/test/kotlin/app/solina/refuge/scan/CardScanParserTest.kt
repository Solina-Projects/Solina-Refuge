package app.solina.refuge.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تست منطق تجزیهٔ اسکن کارت. شماره‌های استفاده‌شده، شماره‌های آزمایشیِ عمومی
 * (Visa/Mastercard/Amex) هستند، نه شمارهٔ واقعی.
 */
class CardScanParserTest {

    // شماره‌های آزمایشی عمومی.
    private val visa = "4111111111111111"
    private val mastercard = "5500005555555559"
    private val amex = "378282246310005"

    @Test fun luhnAcceptsKnownTestNumbers() {
        assertTrue(luhnValid(visa))
        assertTrue(luhnValid(mastercard))
        assertTrue(luhnValid(amex))
    }

    @Test fun luhnRejectsWrongCheckDigitAndShort() {
        assertFalse(luhnValid("4111111111111112"))
        assertFalse(luhnValid("41111111"))
        assertFalse(luhnValid(""))
        assertFalse(luhnValid("4111-1111-1111-1111"))
        assertFalse(luhnValid("41111111111111111111"))
    }

    @Test fun formatGroupsByFour() {
        assertEquals("4111 1111 1111 1111", formatCardNumber(visa))
        assertEquals("3782 8224 6310 005", formatCardNumber(amex))
        assertEquals("", formatCardNumber(""))
        assertEquals("4111 1111", formatCardNumber("4111 1111"))
    }

    @Test fun formatExpiryInsertsSlash() {
        assertEquals("12/28", formatExpiry("1228"))
        assertEquals("12/28", formatExpiry("12/28"))
        assertEquals("12", formatExpiry("12"))
        assertEquals("1", formatExpiry("1"))
        assertEquals("", formatExpiry(""))
    }

    @Test fun validExpiryOnlyMonth01To12() {
        assertTrue(isValidExpiry("12/28"))
        assertTrue(isValidExpiry("01/30"))
        assertFalse(isValidExpiry("13/28"))
        assertFalse(isValidExpiry("00/28"))
        assertFalse(isValidExpiry("12/2"))
        assertFalse(isValidExpiry("1228"))
    }

    @Test fun digitsOnlyConvertsPersianDigits() {
        assertEquals("4111", digitsOnly("۴۱۱۱"))
        assertEquals("4111", digitsOnly("٤١١١"))
        assertEquals("4111111111111111", digitsOnly("4111-1111 1111.1111"))
    }

    @Test fun findCardNumberPlainAndDashed() {
        assertEquals(visa, CardScanParser.findCardNumber("Card $visa"))
        assertEquals(visa, CardScanParser.findCardNumber("4111-1111-1111-1111"))
        assertEquals(amex, CardScanParser.findCardNumber("AMEX $amex"))
        assertEquals(mastercard, CardScanParser.findCardNumber("5500 0055 5555 5559"))
    }

    @Test fun findCardNumberSplitAcrossLines() {
        assertEquals(visa, CardScanParser.findCardNumber("4111 1111\n1111 1111"))
    }

    @Test fun findCardNumberAcceptsOcrConfusionOnlyWhenLuhnPasses() {
        // S به‌جای 5: پس از جایگذاری، Luhn برقرار است.
        assertEquals(mastercard, CardScanParser.findCardNumber("S500 0055 5555 5559"))
        assertEquals(amex, CardScanParser.findCardNumber("37828224631000S"))
        // O به‌جای 0 در انتها: نه خام و نه جایگذاری‌شده Luhn را نمی‌گذرانند — حدس نزن.
        assertNull(CardScanParser.findCardNumber("4111 1111 1111 111O"))
    }

    @Test fun findCardNumberPersianDigits() {
        assertEquals(visa, CardScanParser.findCardNumber("۴۱۱۱ ۱۱۱۱ ۱۱۱۱ ۱۱۱۱"))
    }

    @Test fun findCardNumberRejectsInvalidLuhn() {
        assertNull(CardScanParser.findCardNumber("4111 1111 1111 1112"))
        assertNull(CardScanParser.findCardNumber("no card here"))
        assertNull(CardScanParser.findCardNumber(""))
    }

    @Test fun parseExpirySeparatedForms() {
        assertEquals("12/28", CardScanParser.parseExpiry("VALID THRU 12/28"))
        assertEquals("12/28", CardScanParser.parseExpiry("12-28"))
        assertEquals("12/28", CardScanParser.parseExpiry("12/2028"))
        assertEquals("01/30", CardScanParser.parseExpiry("EXP 01/30"))
        assertNull(CardScanParser.parseExpiry("13/28"))
        assertNull(CardScanParser.parseExpiry("no date"))
        // تکه‌ای از شمارهٔ کارتِ خط‌تیره‌دار/نقطه‌دار نباید تاریخ انقضا شود.
        assertNull(CardScanParser.parseExpiry("4111-1111-1111-1111"))
        assertNull(CardScanParser.parseExpiry("4111.1111.1111.1111"))
    }

    @Test fun parseExpiryKeywordedCompactForm() {
        assertEquals("12/28", CardScanParser.parseExpiry("EXP 1228"))
    }

    @Test fun parseCardTextCombinesNumberAndExpiry() {
        val result = CardScanParser.parseCardText("BANK CARD\n4111 1111 1111 1111\nVALID THRU 12/28\nJOHN DOE")
        assertEquals(visa, result?.cardNumber)
        assertEquals("12/28", result?.expiry)
    }

    @Test fun parseCardTextWithoutNumberIsNull() {
        assertNull(CardScanParser.parseCardText("VALID THRU 12/28"))
    }

    @Test fun parseCardTextWithoutExpiryKeepsNumber() {
        val result = CardScanParser.parseCardText("4111 1111 1111 1111")
        assertEquals(visa, result?.cardNumber)
        assertNull(result?.expiry)
    }
}
