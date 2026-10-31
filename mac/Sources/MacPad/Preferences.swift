import Foundation

/// How much desktop the tablet shows. Logical size = panel pixels / divisor, rendered at 2x.
enum DesktopSize: String, CaseIterable {
    case moreSpace
    case largerText

    var divisor: Double {
        switch self {
        case .moreSpace: return 1.5
        case .largerText: return 2.0
        }
    }

    var title: String {
        switch self {
        case .moreSpace: return "More space"
        case .largerText: return "Larger text"
        }
    }

    /// Logical (point) size of the virtual display for a panel, rounded to even numbers.
    func logicalSize(panelWidth: Int, panelHeight: Int) -> (width: Int, height: Int) {
        func even(_ v: Double) -> Int { Int((v / 2).rounded()) * 2 }
        return (even(Double(panelWidth) / divisor), even(Double(panelHeight) / divisor))
    }
}

enum Preferences {
    private static let sizeKey = "desktopSize"

    static var desktopSize: DesktopSize {
        get { DesktopSize(rawValue: UserDefaults.standard.string(forKey: sizeKey) ?? "") ?? .moreSpace }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: sizeKey) }
    }
}
