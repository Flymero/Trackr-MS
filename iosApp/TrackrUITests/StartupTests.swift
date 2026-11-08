import XCTest

final class StartupTests: XCTestCase {
    func testNativeTabBarSelectionDetailsAndRotation() {
        let app = XCUIApplication()
        app.launchArguments = ["--offline-ui-test"]
        app.launch()
        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(tabBar.waitForExistence(timeout: 30))
        let me = tabBar.buttons["我的"]
        me.tap()
        XCTAssertTrue(me.isSelected)
        XCTAssertTrue(app.switches.containing(NSPredicate(format: "label CONTAINS %@", "长辈版")).firstMatch.waitForExistence(timeout: 10))

        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(me.isHittable)
        XCTAssertLessThanOrEqual(tabBar.frame.maxY, app.frame.maxY)
        XCUIDevice.shared.orientation = .portrait

        // 主题页是子页，原生底栏应释放；返回后重新与共享 Navigator 同步。
        app.buttons.containing(NSPredicate(format: "label BEGINSWITH %@", "主题与颜色")).firstMatch.tap()
        XCTAssertTrue(tabBar.waitForNonExistence(timeout: 10))
        app.buttons["返回"].firstMatch.tap()
        XCTAssertTrue(tabBar.waitForExistence(timeout: 10))
        XCTAssertTrue(tabBar.buttons["我的"].isSelected)
        tabBar.buttons["公交"].tap()
        XCTAssertTrue(tabBar.buttons["公交"].isSelected)
    }

    func testInitializationFailureCanRetryAndReopenStores() {
        let app = XCUIApplication()
        app.launchArguments = ["--offline-ui-test", "--fail-startup-once"]
        app.launch()
        XCTAssertTrue(app.staticTexts["暂时无法打开本地数据，请重试"].waitForExistence(timeout: 30))
        app.buttons["重试"].tap()
        XCTAssertTrue(app.buttons["我的"].firstMatch.waitForExistence(timeout: 30))
    }

    func testOfflineStartupAndRestartRemainUsable() {
        let app = XCUIApplication()
        app.launchArguments = ["--offline-ui-test"]
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30))
        XCTAssertTrue(app.buttons["我的"].firstMatch.waitForExistence(timeout: 30))
        app.buttons["我的"].firstMatch.tap()
        XCTAssertTrue(app.switches.containing(NSPredicate(format: "label CONTAINS %@", "长辈版")).firstMatch.waitForExistence(timeout: 10))
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["我的"].firstMatch.waitForExistence(timeout: 30))
    }
}
