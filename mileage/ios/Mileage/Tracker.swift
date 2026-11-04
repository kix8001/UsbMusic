import CoreLocation
import Foundation

/// GPS で走行距離を測る。バックグラウンドでも計測を続ける（Info.plist の UIBackgroundModes: location）
final class Tracker: NSObject, ObservableObject {
    static let shared = Tracker()

    @Published private(set) var running = false
    @Published private(set) var startTime = Date()
    @Published private(set) var meters: Double = 0
    @Published private(set) var speedKmh: Double = 0
    @Published private(set) var accuracy: Double?
    @Published private(set) var hasFix = false
    @Published var permissionDenied = false

    /// これより悪い精度（m）の位置は使わない
    private let maxAccuracy = 25.0
    /// これを超える速度（m/s、約250km/h）になる位置は飛びとみなして捨てる
    private let maxSpeed = 70.0
    /// これより短い移動は止まっているときの GPS のゆらぎとみなして足さない
    private let minStep = 8.0
    /// これ未満の走行は保存しない（誤操作対策）
    static let minSaveMeters = 50.0

    private let manager = CLLocationManager()
    private var last: CLLocation?
    /// 出発地（最初に取れた良い位置）と、最後に取れた良い位置。住所の自動入力に使う
    private var firstLoc: CLLocationCoordinate2D?
    private var lastLoc: CLLocationCoordinate2D?
    private var pendingStart = false
    private var lastPersist = Date.distantPast
    private let defaults = UserDefaults.standard

    private override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.activityType = .automotiveNavigation
        manager.distanceFilter = 5
        manager.pausesLocationUpdatesAutomatically = false
        // 計測中にアプリが終了されていたら、続きから再開する
        let saved = defaults.double(forKey: "active.start")
        if saved > 0 {
            if defaults.object(forKey: "active.firstLat") != nil {
                firstLoc = CLLocationCoordinate2D(latitude: defaults.double(forKey: "active.firstLat"),
                                                  longitude: defaults.double(forKey: "active.firstLon"))
            }
            begin(start: Date(timeIntervalSince1970: saved), meters: defaults.double(forKey: "active.meters"))
        }
    }

    func start() {
        guard !running else { return }
        switch manager.authorizationStatus {
        case .notDetermined:
            pendingStart = true
            manager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            permissionDenied = true
        default:
            let now = Date()
            defaults.set(now.timeIntervalSince1970, forKey: "active.start")
            defaults.set(0.0, forKey: "active.meters")
            defaults.removeObject(forKey: "active.firstLat")
            defaults.removeObject(forKey: "active.firstLon")
            firstLoc = nil
            lastLoc = nil
            begin(start: now, meters: 0)
        }
    }

    private func begin(start: Date, meters: Double) {
        startTime = start
        self.meters = meters
        last = nil
        hasFix = false
        running = true
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
    }

    func stop() {
        guard running else { return }
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        if meters >= Tracker.minSaveMeters {
            Store.shared.addTrip(Trip(
                start: startTime, end: Date(), meters: meters,
                category: Store.shared.settings.nextCategory,
                startLat: firstLoc?.latitude, startLon: firstLoc?.longitude,
                endLat: lastLoc?.latitude, endLon: lastLoc?.longitude
            ))
            Store.shared.fillMissingAddresses()
        }
        for k in ["active.start", "active.meters", "active.firstLat", "active.firstLon"] {
            defaults.removeObject(forKey: k)
        }
        firstLoc = nil
        lastLoc = nil
        running = false
        meters = 0
        speedKmh = 0
        accuracy = nil
        hasFix = false
        last = nil
    }

    private func handle(_ loc: CLLocation) {
        guard running else { return }
        if loc.speed >= 0 { speedKmh = loc.speed * 3.6 }
        accuracy = loc.horizontalAccuracy
        guard loc.horizontalAccuracy >= 0, loc.horizontalAccuracy <= maxAccuracy else {
            hasFix = false
            return
        }
        hasFix = true
        if firstLoc == nil {
            firstLoc = loc.coordinate
            defaults.set(loc.coordinate.latitude, forKey: "active.firstLat")
            defaults.set(loc.coordinate.longitude, forKey: "active.firstLon")
        }
        lastLoc = loc.coordinate
        guard let prev = last else {
            last = loc
            return
        }
        let d = loc.distance(from: prev)
        let dt = loc.timestamp.timeIntervalSince(prev.timestamp)
        if dt <= 0 { return }
        if d / dt > maxSpeed { return } // 位置が飛んだ：前の点のまま次を待つ
        if d >= max(minStep, loc.horizontalAccuracy) {
            meters += d
            last = loc
        }
        // 短い移動はためておき、しきい値を超えたらまとめて足す
        if Date().timeIntervalSince(lastPersist) > 5 {
            lastPersist = Date()
            defaults.set(meters, forKey: "active.meters")
        }
    }
}

extension Tracker: CLLocationManagerDelegate {
    // CLLocationManager はメインスレッドで作ったので、通知もメインスレッドに来る
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for loc in locations { handle(loc) }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard pendingStart else { return }
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            pendingStart = false
            start()
        case .denied, .restricted:
            pendingStart = false
            permissionDenied = true
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        hasFix = false
    }
}
