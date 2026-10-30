set(PLATFORM_SOURCES 3rdparty/WinCommander.cpp src/sys/windows/guihelper.cpp src/sys/windows/MiniDump.cpp src/sys/windows/eventHandler.cpp src/sys/windows/WinVersion.cpp src/sys/windows/AutoRun.cpp src/sys/windows/UrlScheme.cpp src/sys/windows/SystemProxy.cpp src/sys/windows/GlobalHotkeys.cpp)
set(PLATFORM_LIBRARIES wininet wsock32 ws2_32 user32 rasapi32 iphlpapi ntdll wbemuuid psapi shell32)

include(cmake/windows/generate_product_version.cmake)
if (ONLINESETI_EDITION)
    set(EDITION_NAME "OnlineSeti VPN")
    set(EDITION_FILE "OnlineSetiVPN.exe")
    set(EDITION_COMPANY "OnlineSeti")
    set(EDITION_ICON "${CMAKE_SOURCE_DIR}/res/OnlineSeti.ico")
else()
    set(EDITION_NAME "qThrone")
    set(EDITION_FILE "qThrone.exe")
    set(EDITION_COMPANY "qThrone")
    set(EDITION_ICON "${CMAKE_SOURCE_DIR}/res/Throne.ico")
endif()
generate_product_version(
        QV2RAY_RC
        ICON "${EDITION_ICON}"
        NAME "${EDITION_NAME}"
        BUNDLE "${EDITION_NAME}"
        COMPANY_NAME "${EDITION_COMPANY}"
        COMPANY_COPYRIGHT "${EDITION_COMPANY}"
        FILE_DESCRIPTION "${EDITION_NAME}"
        ORIGINAL_FILENAME "${EDITION_FILE}"
        VERSION_MAJOR ${NKR_VERSION_MAJOR}
        VERSION_MINOR ${NKR_VERSION_MINOR}
        VERSION_PATCH ${NKR_VERSION_PATCH}
        VERSION_REVISION ${NKR_VERSION_REVISION}
)
add_definitions(-DUNICODE -D_UNICODE -DNOMINMAX)
set(GUI_TYPE WIN32)
if (MSVC)
    add_compile_options("/utf-8")
    add_definitions(-D_WIN32_WINNT=0x600 -D_SCL_SECURE_NO_WARNINGS -D_CRT_SECURE_NO_WARNINGS)
endif ()
