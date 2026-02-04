cmake_minimum_required(VERSION 3.16)

project(newwww VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD_REQUIRED ON)

# find_package(Qt6 REQUIRED COMPONENTS Quick)
find_package(Qt6 REQUIRED COMPONENTS Core Gui Qml Quick Sql Location Positioning Sensors)

qt_standard_project_setup(REQUIRES 6.8)

qt_add_executable(appnewwww
    main.cpp
)

qt_add_qml_module(appnewwww
    URI newwww
    QML_FILES
        Main.qml
        QML_FILES main.qml
)

# Qt for iOS sets MACOSX_BUNDLE_GUI_IDENTIFIER automatically since Qt 6.1.
# If you are developing for iOS or macOS you should consider setting an
# explicit, fixed bundle identifier manually though.
set_target_properties(appnewwww PROPERTIES
#    MACOSX_BUNDLE_GUI_IDENTIFIER com.example.appnewwww
    MACOSX_BUNDLE_BUNDLE_VERSION ${PROJECT_VERSION}
    MACOSX_BUNDLE_SHORT_VERSION_STRING ${PROJECT_VERSION_MAJOR}.${PROJECT_VERSION_MINOR}
    MACOSX_BUNDLE TRUE
    WIN32_EXECUTABLE TRUE
)

# target_link_libraries(appnewwww
#     PRIVATE Qt6::Quick
# )

target_link_libraries(appnewwww
    PRIVATE Qt6::Core Qt6::Gui Qt6::Qml Qt6::Quick Qt6::Sql Qt6::Location Qt6::Positioning Qt6::Sensors
)

include(GNUInstallDirs)
install(TARGETS appnewwww
    BUNDLE DESTINATION .
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)
