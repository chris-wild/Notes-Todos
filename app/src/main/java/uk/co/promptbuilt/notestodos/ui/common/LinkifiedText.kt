package uk.co.promptbuilt.notestodos.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

// Port of src/utils/renderWithLinks.js: URLs in plain text become tappable links
// (opened via the platform UriHandler); everything else renders as-is.
private val URL_REGEX = Regex("""https?://\S+""")

fun linkify(text: String, linkStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in URL_REGEX.findAll(text)) {
        append(text, last, match.range.first)
        withLink(LinkAnnotation.Url(match.value, TextLinkStyles(style = linkStyle))) {
            append(match.value)
        }
        last = match.range.last + 1
    }
    append(text, last, text.length)
}

@Composable
fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        linkify(text, SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    }
    Text(text = annotated, modifier = modifier, style = style)
}
