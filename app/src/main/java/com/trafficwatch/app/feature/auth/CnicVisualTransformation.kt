package com.trafficwatch.app.feature.auth

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Renders raw CNIC digits as 12345-1234567-1 for display; underlying state stays raw digits. */
class CnicVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = formatCnicWithDashes(text.text)
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                var transformed = offset
                if (offset > 5) transformed++
                if (offset > 12) transformed++
                return transformed
            }

            override fun transformedToOriginal(offset: Int): Int {
                var original = offset
                if (offset > 6) original--
                if (offset > 14) original--
                return original.coerceIn(0, text.text.length)
            }
        }
        return TransformedText(AnnotatedString(formatted), offsetMapping)
    }
}
