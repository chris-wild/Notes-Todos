import StoreKitTest
import XCTest

/// Drives the app to the paywall and holds it on screen. SKTestSession loads the bundled
/// Products.storekit into the simulator's local StoreKit environment (and throws loudly on
/// a malformed file), so the pack prices render for real — scripts/ios-paywall-screenshot.sh
/// photographs the simulator from the outside while this test holds the sheet open
/// (App Store review screenshots for the IAPs).
final class PaywallScreenshotTest: XCTestCase {

    @MainActor
    func testHoldPaywallOpen() throws {
        let configURL = Bundle(for: Self.self).url(forResource: "Products", withExtension: "storekit")
        let session = try SKTestSession(contentsOf: try XCTUnwrap(configURL, "Products.storekit missing from the test bundle"))
        session.resetToDefaultState()
        session.disableDialogs = true

        let app = XCUIApplication()
        app.launch()

        let recipesTab = app.tabBars.buttons["Recipes"]
        XCTAssertTrue(recipesTab.waitForExistence(timeout: 10), "tab bar never appeared")
        recipesTab.tap()
        let settings = app.buttons["Settings"]
        if !settings.waitForExistence(timeout: 5) {
            // The first tap right after launch is sometimes swallowed by SwiftUI's tab bar.
            recipesTab.tap()
        }
        XCTAssertTrue(settings.waitForExistence(timeout: 10), "Recipes toolbar never appeared")
        settings.tap()
        let buy = app.buttons["Buy credits"]
        XCTAssertTrue(buy.waitForExistence(timeout: 10), "Settings sheet never appeared")
        buy.tap()

        // The pack rows appear once StoreKit answers; a price button proves prices rendered
        // (symbol left open: the local StoreKit config may format in the sim's locale).
        let price = app.buttons.matching(NSPredicate(format: "label CONTAINS '1.99'")).firstMatch
        XCTAssertTrue(price.waitForExistence(timeout: 60), "credit packs never loaded — is the StoreKit configuration attached to the TEST action? (scripts/ios-paywall-screenshot.sh patches it)")

        // Hold the sheet for the outside screenshot pass.
        Thread.sleep(forTimeInterval: 25)
    }
}
