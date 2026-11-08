import UIKit
import ComposeApp

#if !targetEnvironment(simulator)
import MAMapKit
import AMapFoundationKit

/// Receives GCJ-02 geometry. It never starts its own location manager.
final class NativeRouteMap: UIView, MAMapViewDelegate {
    private static let instances = NSHashTable<NativeRouteMap>.weakObjects()
    private var map: MAMapView?
    private var styles: [ObjectIdentifier: (UIColor, CGFloat)] = [:]
    private var lastPayload = ""

    static func install() {
        IosNativeBridgeKt.installIosMap(create: { key in NativeRouteMap(key: key) }, update: { view, payload, inset in
            (view as? NativeRouteMap)?.render(payload, bottomInset: inset.doubleValue)
        }, release: { view in
            (view as? NativeRouteMap)?.close()
        }, privacy: { agreed in
            MAMapView.updatePrivacyShow(.didShow, privacyInfo: .didContain)
            MAMapView.updatePrivacyAgree(agreed.boolValue ? .didAgree : .notAgree)
            if !agreed.boolValue { instances.allObjects.forEach { $0.close() } }
        })
    }

    init(key: String) {
        super.init(frame: .zero)
        Self.instances.add(self)
        MAMapView.updatePrivacyShow(.didShow, privacyInfo: .didContain)
        MAMapView.updatePrivacyAgree(.didAgree)
        AMapServices.shared().apiKey = key
        let map = MAMapView(frame: bounds)
        map.delegate = self
        map.showsUserLocation = false
        map.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(map)
        self.map = map
        accessibilityLabel = "路线地图"
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func close() {
        map?.delegate = nil
        map?.showsUserLocation = false
        map?.removeFromSuperview()
        map = nil
        styles.removeAll()
    }

    func render(_ payload: String, bottomInset: Double) {
        guard payload != lastPayload, let map, let data = payload.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        lastPayload = payload
        map.removeOverlays(map.overlays)
        map.removeAnnotations(map.annotations)
        styles.removeAll()
        var points: [CLLocationCoordinate2D] = []
        for item in root["overlays"] as? [[String: Any]] ?? [] {
            let kind = item["kind"] as? String ?? ""
            var coordinates = (item["points"] as? [[Double]] ?? []).compactMap { pair -> CLLocationCoordinate2D? in
                guard pair.count == 2, pair.allSatisfy({ $0.isFinite }) else { return nil }
                return CLLocationCoordinate2D(latitude: pair[0], longitude: pair[1])
            }
            if kind != "alternative" { points.append(contentsOf: coordinates) }
            if kind == "marker", let coordinate = coordinates.first {
                let annotation = MAPointAnnotation()
                annotation.coordinate = coordinate
                annotation.title = item["title"] as? String
                map.addAnnotation(annotation)
            } else if coordinates.count >= 2, let line = MAPolyline(coordinates: &coordinates, count: UInt(coordinates.count)) {
                let colors: [UIColor] = [.systemBlue, .systemOrange, .systemPurple]
                let color = kind == "alternative" ? UIColor.systemGray : kind == "walk" ? .systemTeal : colors[(item["leg"] as? Int ?? 0) % colors.count]
                styles[ObjectIdentifier(line)] = (color, kind == "alternative" ? 2 : 6)
                map.add(line)
            }
        }
        if points.isEmpty, let center = root["center"] as? [Double], center.count == 2 {
            map.setCenter(CLLocationCoordinate2D(latitude: center[0], longitude: center[1]), animated: false)
            map.setZoomLevel(14, animated: false)
        } else if let first = points.first {
            let origin = MAMapPointForCoordinate(first)
            let rect = points.dropFirst().reduce(MAMapRectMake(origin.x, origin.y, 1, 1)) { rect, point in
                let converted = MAMapPointForCoordinate(point)
                return MAMapRectUnion(rect, MAMapRectMake(converted.x, converted.y, 1, 1))
            }
            map.setVisibleMapRect(rect, edgePadding: UIEdgeInsets(top: 120, left: 32, bottom: max(bottomInset, 160), right: 32), animated: false)
        }
    }

    func mapView(_ mapView: MAMapView!, rendererFor overlay: MAOverlay!) -> MAOverlayRenderer! {
        guard let line = overlay as? MAPolyline else { return nil }
        let renderer = MAPolylineRenderer(polyline: line)
        let style = styles[ObjectIdentifier(line)] ?? (.systemBlue, 6)
        renderer?.strokeColor = style.0
        renderer?.lineWidth = style.1
        return renderer
    }
}
#else
// AMap's official binary has x86_64 simulator + arm64 device slices, no arm64 simulator.
// Keep Web planning available on Apple Silicon; do not link a device binary into the simulator.
enum NativeRouteMap { static func install() {} }
#endif
