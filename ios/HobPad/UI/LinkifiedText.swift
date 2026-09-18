import SwiftUI

/// Port of the shared apps' URL auto-linking: plain text in, tappable links out.
struct LinkifiedText: View {
    let text: String

    var body: some View {
        Text(Self.linkify(text))
    }

    static func linkify(_ text: String) -> AttributedString {
        var attributed = AttributedString(text)
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else {
            return attributed
        }
        let ns = text as NSString
        for match in detector.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            guard let url = match.url,
                  let range = Range(match.range, in: text),
                  let attrRange = attributed.range(of: String(text[range])) else { continue }
            attributed[attrRange].link = url
            attributed[attrRange].foregroundColor = .blue
            attributed[attrRange].underlineStyle = .single
        }
        return attributed
    }
}
