import QtQuick
import QtQuick.Controls
import QtLocation
import QtPositioning

ApplicationWindow {
    width: 360
    height: 640
    visible: true
    title: "Offline Map GPS"

    // 1. 定位组件
    PositionSource {
        id: gps
        active: true
        updateInterval: 1000 // 1秒更新一次
        preferredPositioningMethods: PositionSource.SatellitePositioningMethods

        onPositionChanged: {
            // 当位置变化时，强制地图中心跟随（可选）
            // map.center = position.coordinate
        }
    }

    // 2. 地图组件
    Map {
        id: map
        anchors.fill: parent

        // 核心技巧：使用 osm 插件，但自定义 UrlTemplate
        plugin: Plugin {
            name: "osm"
            // 必须禁用默认的在线地图源，否则会覆盖我们的离线图
            PluginParameter { name: "osm.mapping.providersrepository.disabled"; value: true }
        }

        // 默认中心点（比如北京），实际会被 GPS 覆盖
        center: QtPositioning.coordinate(39.90, 116.40)
        zoomLevel: 14

        // --- 核心：加载离线 .db 瓦片 ---
        activeMapType: MapType {
            name: "OfflineDB"
            style: MapType.CustomMap
            // 这里的 url 格式必须对应 C++ requestImage 里的解析逻辑
            // image://[provider名字]/[z]/[x]/[y]
            // Qt Location 会自动把 {z} {x} {y} 替换成数字
            mobile: true
            night: false
        }

        // 强制覆盖图层 URL (这是 Qt 5.14+ 后的关键属性)
        Component.onCompleted: {
            for (var i = 0; i < map.supportedMapTypes.length; i++) {
                if (map.supportedMapTypes[i].name === "Custom URL Map") {
                    map.activeMapType = map.supportedMapTypes[i]
                }
            }
        }

        // 简单暴力法：添加一个图层覆盖在上面
        MapParameter {
            type: "source"
            property var format: "image"
            property var url: "image://offlinemap/{z}/{x}/{y}"
        }

        // 显示手机当前位置的小蓝点
        MapQuickItem {
            coordinate: gps.position.coordinate
            anchorPoint.x: image.width / 2
            anchorPoint.y: image.height / 2

            sourceItem: Rectangle {
                id: image
                width: 20; height: 20
                color: "blue"
                radius: 10
                border.color: "white"
                border.width: 3
            }
        }
    }

    // 3. 仪表盘：显示经纬度
    Rectangle {
        anchors.bottom: parent.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        height: 150
        color: "#CC000000" // 半透明黑

        Column {
            anchors.centerIn: parent
            spacing: 10

            Text {
                text: "Lat: " + gps.position.coordinate.latitude.toFixed(6)
                color: "white"
                font.pixelSize: 20
                font.bold: true
            }
            Text {
                text: "Lon: " + gps.position.coordinate.longitude.toFixed(6)
                color: "white"
                font.pixelSize: 20
                font.bold: true
            }
            Text {
                text: "Alt: " + gps.position.coordinate.altitude.toFixed(1) + " m"
                color: "lightgray"
                font.pixelSize: 16
            }

            Button {
                text: "我按哪儿了?"
                onClicked: {
                    map.center = gps.position.coordinate
                    map.zoomLevel = 16
                }
            }
        }
    }
}
