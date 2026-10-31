#include <QAbstractTextDocumentLayout>
#include <QTextDocument>
#include <QtMath>
#include "include/ui/mainwindow.h"
#include "NkrVersion.h"

#include "include/ui/mainWindow/MainWindowInternal.h"
#include "include/api/RPC.h"
#include "include/api/remote/Router.hpp"
#include "include/api/remote/Server.hpp"
// Full definition: MainWindow's destructor lives here and destroys the unique_ptr.
#include "include/ui/mainWindow/TestRunner.h"

#include <QMenu>

#include "include/configs/sub/GroupUpdater.hpp"
#include "include/configs/sub/RouteUpdater.hpp"
#include "include/global/PeriodicRunner.hpp"
#include "include/global/Logger.hpp"
#include "include/scanner/IpListUpdater.h"
#include "include/scanner/ScanManager.h"
#include "include/stats/autoselector/AutoSelectorMonitor.hpp"
#include "include/ui/stats/dialog_auto_selector.h"
#include "include/sys/KillSwitch.hpp"
#include "include/sys/Process.hpp"
#include "include/sys/AutoRun.hpp"
#include "include/sys/UrlScheme.hpp"

#include "include/ui/setting/ThemeManager.hpp"
#include "include/ui/setting/Icon.hpp"
#include "include/ui/stats/dialog_traffic_stats.h"
#include "include/ui/stats/RuntimeStatsWidget.h"
#include "include/ui/widget/StartStopButton.hpp"

#include "include/configs/generate.h"
#include "include/database/GroupsRepo.h"
#include "include/database/ProfilesRepo.h"
#include "include/database/RoutesRepo.h"
#include "include/global/Common.h"

#include "include/ui/utils/ProfilesTableFilterHeader.h"
#include "include/ui/utils/ProfilesTableModel.h"

#include "include/ui/group/dialog_edit_group.h"

#ifdef Q_OS_WIN
#include <windows.h>
// <windows.h> defines SetPort, which under unity builds clobbers Configs::outbound::SetPort.
#undef SetPort
#else
#ifdef Q_OS_LINUX
#include <QDBusInterface>
#include <QDBusReply>
#include <sys/socket.h>
#endif
#ifdef Q_OS_MACOS
#include <sys/socket.h>
#include <sys/un.h>
#endif
#include <unistd.h>
#endif

#include <QUuid>

#include <QClipboard>
#include <QScrollArea>
#include <QScrollBar>
#include <QDesktopServices>
#include <QTimer>
#include <QMessageBox>
#include <QDir>
#if QT_VERSION >= QT_VERSION_CHECK(6, 5, 0)
#include <QStyleHints>
#endif
#include <QFileDialog>
#include <QToolButton>
#include <QFrame>
#include <QGridLayout>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QStackedWidget>
#include <QLabel>
#include <QPixmap>
#include <QPushButton>
#include <QStyle>
#include <QTextBrowser>
#include <include/global/HTTPRequestHelper.hpp>
#include "include/global/DeviceDetailsHelper.hpp"

void UI_InitMainWindow() {
    mainwindow = new MainWindow;
}

// Caller must hold coreProcessMutex (reads core_process lock-free by design).
bool MainWindow::verify_core_pid(QLocalSocket *socket) {
    if (!core_process) return false;
    qint64 expectedPid = core_process->processId();
    if (expectedPid <= 0) return false;

#if defined(Q_OS_LINUX)
    struct ucred cred = {};
    socklen_t credLen = sizeof(cred);
    if (getsockopt(static_cast<int>(socket->socketDescriptor()), SOL_SOCKET, SO_PEERCRED, &cred, &credLen) == 0) {
        return static_cast<qint64>(cred.pid) == expectedPid;
    }
    return false;
#elif defined(Q_OS_MACOS)
    pid_t pid = 0;
    socklen_t pidLen = sizeof(pid);
    if (getsockopt(static_cast<int>(socket->socketDescriptor()), SOL_LOCAL, LOCAL_PEERPID, &pid, &pidLen) == 0) {
        return static_cast<qint64>(pid) == expectedPid;
    }
    return false;
#elif defined(Q_OS_WIN)
    ULONG pid = 0;
    HANDLE hPipe = reinterpret_cast<HANDLE>(static_cast<qintptr>(socket->socketDescriptor()));
    if (GetNamedPipeClientProcessId(hPipe, &pid)) {
        return static_cast<qint64>(pid) == expectedPid;
    }
    return false;
#else
    Q_UNUSED(socket)
    return true;
#endif
}

static bool themeUsesDarkLog(const QString &theme) {
    const auto lower = theme.toLower();
    if (lower.contains("vista") || lower.contains("flatgray") || lower.contains("lightblue") || lower.contains("softpink")) {
        return false;
    }
    if (lower.contains("qdarkstyle") || lower.contains("blacksoft") || lower.contains("onlineseti")) {
        return true;
    }
    return isDarkMode();
}

MainWindow::MainWindow(QWidget *parent) : QMainWindow(parent), ui(new Ui::MainWindow) {
    mainwindow = this;
    setAcceptDrops(true);
    MW_dialog_message = [=,this](MwMessage cmd, QStringList args) {
        runOnUiThread([=,this]
        {
            dialog_message_impl(cmd, args);
        });
    };
    MW_handle_deeplink = [=,this](const QString &url) {
        runOnUiThread([=,this]
        {
            handle_deeplink_impl(url);
        });
    };
    MW_import_files = [=,this](const QStringList &paths) {
        runOnUiThread([=,this]
        {
            importFromFiles(paths);
        });
    };

    AutoRun_FixTaskIfNeeded();
    AutoRun_MigrateIfNeeded();

    UrlScheme_RegisterIfNeeded();

    bool isNum;
    Configs::dataManager->settingsRepo->theme.toInt(&isNum);
    if (isNum) {
#ifdef ONLINESETI_EDITION
        const auto storedTheme = Configs::dataManager->settingsRepo->theme;
        Configs::dataManager->settingsRepo->theme = storedTheme == "0" ? "OnlineSeti" : "System";
#else
        Configs::dataManager->settingsRepo->theme = "System";
#endif
        Configs::dataManager->settingsRepo->Save();
    }
    themeManager()->ApplyTheme(Configs::dataManager->settingsRepo->theme);
    ui->setupUi(this);

    setActionsData();
    loadShortcuts();

    last_running_profile_id = Configs::dataManager->settingsRepo->remember_id;

    if (!Configs::dataManager->settingsRepo->mainWindowGeometry.isEmpty()) {
        auto geo = DecodeB64IfValid(Configs::dataManager->settingsRepo->mainWindowGeometry);
        this->restoreGeometry(geo);
    }

    ui->splitter->restoreState(DecodeB64IfValid(Configs::dataManager->settingsRepo->splitter_state));
    setLogHighlighter(themeUsesDarkLog(Configs::dataManager->settingsRepo->theme));
    setupLogView();
    updateLogFilterFields();
    runOnThread([=, this] {
        log_process_loop();
    }, LogThread);

#if QT_VERSION >= QT_VERSION_CHECK(6, 5, 0)
    connect(qApp->styleHints(), &QStyleHints::colorSchemeChanged, this, [=,this](const Qt::ColorScheme& scheme) {
        setLogHighlighter(scheme == Qt::ColorScheme::Dark);
        themeManager()->ApplyTheme(Configs::dataManager->settingsRepo->theme, true);
    });
#endif
    connect(themeManager(), &ThemeManager::themeChanged, this, [=,this](const QString& theme){
        setLogHighlighter(themeUsesDarkLog(theme));
        applyLogBrowserFont();
        scheduleProxyListRefresh();
    });
    MW_show_log = [=,this](const QString &log) {
        append_log(log);
        Logging::WriteUserLog(log);
    };

    if (Configs::dataManager->settingsRepo->random_inbound_port)
    {
        Configs::dataManager->settingsRepo->inbound_socks_port = MkPort(Configs::dataManager->settingsRepo->inbound_address);
    }

    runOnNewThread([=, this] {GetDeviceDetails(); });

    auto core_path = Configs::FindCoreRealPath();

    bool coreDebugMode = (Configs::dataManager->settingsRepo->log_level == "debug");

    Configs::dataManager->settingsRepo->core_socket_name =
        "throneIPC-" + QUuid::createUuid().toString(QUuid::WithoutBraces);
    core_server = new QLocalServer(this);
    core_server->setSocketOptions(QLocalServer::UserAccessOption);
    if (!core_server->listen(Configs::dataManager->settingsRepo->core_socket_name)) {
        qWarning() << "Failed to start IPC server:" << core_server->errorString();
        qApp->quit();
    }

    connect(core_server, &QLocalServer::newConnection, this, [=, this]() {
        auto socket = core_server->nextPendingConnection();
        int profileId = -1;
        {
            // Hold coreProcessMutex: DS_cores may still be constructing core_process.
            QMutexLocker lock(&coreProcessMutex);
            if (!verify_core_pid(socket)) {
                MW_show_log("[Warn] IPC connection from unexpected process rejected");
                socket->close();
                socket->deleteLater();
                return;
            }
            if (core_process) {
                profileId = core_process->start_profile_when_core_is_up;
                core_process->start_profile_when_core_is_up = -1;
            }
        }
        setup_rpc(socket);
        Configs::dataManager->settingsRepo->core_running = true;
        LOG_INFO(QString("elevated: %1").arg(Configs::IsAdmin() ? "yes" : "no"));
        MW_dialog_message(MwMessage::CoreStarted, {Int2String(profileId)});
    });

    // Must precede the first CoreStarted, or a remembered profile would start before the guard is even arming.
    Sys::KillSwitch::instance()->apply();

    auto socketFullName = core_server->fullServerName();
    runOnThread(
        [=, this] {
            QMutexLocker lock(&coreProcessMutex);
            core_process = new Configs_sys::CoreProcess(core_path, socketFullName, coreDebugMode);
            if (Configs::dataManager->settingsRepo->remember_enable &&
                Configs::dataManager->settingsRepo->remember_id >= 0) {
                core_process->start_profile_when_core_is_up =
                    Configs::dataManager->settingsRepo->remember_id;
            }
            core_process->Start();
        },
        DS_cores);

    if (!Configs::dataManager->settingsRepo->font.isEmpty()) {
        auto font = qApp->font();
        font.setFamily(Configs::dataManager->settingsRepo->font);
        qApp->setFont(font);
    }
    if (Configs::dataManager->settingsRepo->font_size != 0) {
        auto font = qApp->font();
        font.setPointSize(Configs::dataManager->settingsRepo->font_size);
        qApp->setFont(font);
    }

    parallelCoreCallPool->setMaxThreadCount(10);
    testRunner = std::make_unique<TestRunner>(this);
    Subscription::updater()->SetUrlTester([this](const QList<int> &profileIDs, const Subscription::GroupUpdater::Finish &done) {
        testRunner->queueUrlTests(profileIDs, done);
    });
    ui->menu_start->setShortcuts({QKeySequence(Qt::Key_Return), QKeySequence(Qt::Key_Enter)});
    connect(ui->menu_start, &QAction::triggered, this, [=,this]() { profile_start(); });
    connect(ui->menu_stop, &QAction::triggered, this, [=,this]() { profile_stop(false, false, true); });
    connect(ui->toolButton_startstop, &QAbstractButton::clicked, this, [=,this]() {
        if (running != nullptr) profile_stop(false, false, true);
        else profile_start();
    });
    ui->toolButton_startstop->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(ui->toolButton_startstop, &QWidget::customContextMenuRequested, this, [this] { show_startstop_menu(); });
    connect(ui->tabWidget->tabBar(), &QTabBar::tabMoved, this, [=,this](int from, int to) {
        QList<int> tabOrder;
        for (int i = 0; i < ui->tabWidget->tabBar()->count(); i++) {
            tabOrder += ui->tabWidget->tabBar()->tabData(i).toInt();
        }
        Configs::dataManager->groupsRepo->SetGroupsTabOrder(tabOrder);
        on_tabWidget_currentChanged(ui->tabWidget->tabBar()->currentIndex());
    });
    ui->label_running->installEventFilter(this);
    ui->label_inbound->installEventFilter(this);
    ui->splitter->installEventFilter(this);
    // Never from a mouse-press filter: off Windows Qt synthesizes the context-menu event after the press (#1642).
    ui->tabWidget->setContextMenuPolicy(Qt::CustomContextMenu);
    ui->tabWidget->tabBar()->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(ui->tabWidget->tabBar(), &QWidget::customContextMenuRequested, this,
            [this](const QPoint& pos) { show_group_tab_menu(pos); });
    auto btnFilter = new QToolButton(this);
    btnFilter->setIcon(QIcon(":/icon/filter.png"));
    btnFilter->setToolTip(QString("%1\n%2").arg(tr("Enable Filter"), QKeySequence(QKeySequence::Find).toString(QKeySequence::NativeText)));
    btnFilter->setShortcut(QKeySequence::Find);
    btnFilter->setCheckable(true);
    // Sits inside the tab strip: an ignored right-click here would propagate to the group menu.
    btnFilter->setContextMenuPolicy(Qt::PreventContextMenu);
    connect(btnFilter, &QToolButton::toggled, static_cast<ProfilesTableFilterHeader*>(ui->profilesTableView->horizontalHeader()), &ProfilesTableFilterHeader::setFiltersVisible);
    connect(static_cast<ProfilesTableFilterHeader*>(ui->profilesTableView->horizontalHeader()), &ProfilesTableFilterHeader::closeRequested,
            btnFilter, [btnFilter] { btnFilter->setChecked(false); });
    ui->tabWidget->setCornerWidget(btnFilter, Qt::TopRightCorner);
    // A direct QSplitter child is automatically inserted as another splitter pane.
    // Keep this passive overlay outside the splitter's managed children.
    seasonalDecoration = installSeasonalDecoration(ui->centralwidget, ui->horizontalLayout_2, ui->tabWidget->tabBar(), btnFilter);
    seasonalDecoration->setDecoration(Configs::dataManager->settingsRepo->seasonal_decoration);
    RegisterHotkey(false);
    auto last_size = Configs::dataManager->settingsRepo->mw_size.split("x");
    if (last_size.length() == 2) {
        auto w = last_size[0].toInt();
        auto h = last_size[1].toInt();
        if (w > 0 && h > 0) {
            resize(w, h);
        }
    }

#ifdef ONLINESETI_EDITION
    software_name = "OnlineSeti VPN";
#else
    software_name = "qThrone";
#endif
    software_core_name = "sing-box";
    if (auto dashDir = QDir("dashboard"); !dashDir.exists() && QDir().mkdir("dashboard")) {
        if (auto dashFile = QFile(":/Throne/dashboard-notice.html"); dashFile.exists() && dashFile.open(QIODevice::ReadOnly))
        {
            auto data = dashFile.readAll();
            if (auto dest = QFile("dashboard/index.html"); dest.open(QIODevice::Truncate | QIODevice::WriteOnly))
            {
                dest.write(data);
                dest.close();
            }
            dashFile.close();
        }
    }
    // Leaving the dir non-empty marks it user-provided, disabling the core's own updater.
    SeedDashboard();
    if (auto iconsDir = QDir("icons"); !iconsDir.exists()) {
        QDir().mkdir("icons") ? qDebug("created icons dir") : qDebug("Failed to create icons dir");
    }

    ui->toolButton_program->setMenu(ui->menu_program);
    ui->toolButton_preferences->setMenu(ui->menu_preferences);
    ui->toolButton_routing->setMenu(ui->menuRouting_Menu);
    ui->toolButton_testing->setMenu(ui->menuTesting);
    ui->toolButton_tools->setMenu(ui->menuTools);
    ui->toolButton_program->installEventFilter(this);

    ui->tabWidget->setMinimumHeight(100);
    ui->stats_widget->setMinimumHeight(100);
    setMinimumHeight(qMax(minimumHeight(), 360));
    designMinimumSize = minimumSize();
    applyTopBarMetrics();

    ui->menubar->setVisible(false);
    connect(ui->actionRuntime_Stats, &QAction::triggered, this, [=, this]() {
        ui->stats_widget->setCurrentWidget(ui->runtime_tab);
        if (ui->splitter->sizes().value(1) < ui->stats_widget->tabBar()->sizeHint().height()) {
            const auto height = ui->splitter->size().height();
            ui->splitter->setSizes({height / 2, height / 2});
        }
    });
    ui->actionTraffic_Stats->setVisible(!Configs::dataManager->settingsRepo->disable_traffic_aggregation);
    connect(ui->actionTraffic_Stats, &QAction::triggered, this, [=, this]() {
        USE_DIALOG(DialogTrafficStats)
    });
    ui->actionAuto_Selector->setVisible(false);
    connect(ui->actionAuto_Selector, &QAction::triggered, this, [=,this]() {
        if (m_autoSelectorDialog == nullptr) {
            m_autoSelectorDialog = new DialogAutoSelector(this);
            connect(m_autoSelectorDialog, &QDialog::finished, this, [this] {
                m_autoSelectorDialog->deleteLater();
                m_autoSelectorDialog = nullptr;
            });
        }
        m_autoSelectorDialog->refresh();
        m_autoSelectorDialog->show();
        m_autoSelectorDialog->raise();
        m_autoSelectorDialog->activateWindow();
    });
    connect(ui->actionCheck_For_Update, &QAction::triggered, this, [=,this] { runOnNewThread([=,this] { CheckUpdate(); }); });
    connect(ui->actionUpdate_Rule_Sets, &QAction::triggered, this, [=,this] {
        if (m_ruleSetUpdateBusy) return;
        m_ruleSetUpdateBusy = true;
        runOnNewThread([=,this] {
            bool rpcOK = false;
            int updated = 0;
            const auto error = API::defaultClient->UpdateRuleSets(&rpcOK, &updated);
            runOnUiThread([=,this] {
                m_ruleSetUpdateBusy = false;
                if (!rpcOK) {
                    MessageBoxWarning(tr("Update Rule-Sets"), error);
                    return;
                }
                const auto summary = tr("%n remote rule-set(s) refreshed", nullptr, updated);
                if (!error.isEmpty()) {
                    MessageBoxWarning(tr("Update Rule-Sets"), summary + "\n\n" + error);
                } else {
                    MessageBoxInfo(tr("Update Rule-Sets"), summary);
                }
            });
        });
    });
    // Checking releases also supports manual installation (AppData/macOS),
    // so it must not depend on the presence or name of an updater executable.
    ui->actionCheck_For_Update->setEnabled(!QStringLiteral(QTHRONE_REPOSITORY).isEmpty());

    setupConnectionList();
    ui->stats_widget->tabBar()->setCurrentIndex(Configs::dataManager->settingsRepo->stats_tab);
    connect(ui->stats_widget->tabBar(), &QTabBar::currentChanged, this, [=,this](int index)
    {
        Configs::dataManager->settingsRepo->stats_tab = ui->stats_widget->tabBar()->currentIndex();
        syncConnectionViewState();
    });
    syncConnectionViewState();

    speedChartWidget = new SpeedWidget(this);
    ui->graph_tab->layout()->addWidget(speedChartWidget);

    runtimeStatsWidget = new RuntimeStatsWidget(this);
    auto* runtimeScroll = new QScrollArea(this);
    runtimeScroll->setFrameShape(QFrame::NoFrame);
    runtimeScroll->setWidgetResizable(true);
    runtimeScroll->setWidget(runtimeStatsWidget);
    ui->runtime_tab->layout()->addWidget(runtimeScroll);

    profilesTableModel = new ProfilesTableModel(this);
    // A profile fed by an IP list shows the list's first entry, which any list change can move.
    const auto refreshEffectiveAddresses = [this] {
        Configs::InvalidateEndpointDisplayCache();
        profilesTableModel->invalidateAddresses();
    };
    connect(Scanner::IpListUpdater::instance(), &Scanner::IpListUpdater::listsChanged, this, refreshEffectiveAddresses,
            Qt::QueuedConnection);
    connect(Scanner::IpListUpdater::instance(), &Scanner::IpListUpdater::listUpdated, this, refreshEffectiveAddresses,
            Qt::QueuedConnection);
    profilesFilterModel = new ProfilesFilterProxyModel(this);
    profilesFilterModel->setSourceModel(profilesTableModel);
    ui->profilesTableView->setModel(profilesFilterModel);
    connect(ui->profilesTableView->selectionModel(), &QItemSelectionModel::selectionChanged, this,
            [this] { refresh_startstop_button(); });
    ui->profilesTableView->rowsSwapped = [this](int row1, int row2)
    {
        // A drop position in a filtered list says nothing about the group's real order.
        if (profilesFilterModel->hasActiveFilter()) return;
        if (row1 == row2) return;
        auto group = Configs::dataManager->groupsRepo->CurrentGroup();
        group->EmplaceProfile(row1, row2);
        profilesTableModel->emplaceProfiles(row1, row2);
        Configs::dataManager->groupsRepo->Save(group);
    };
    connect(ui->profilesTableView->horizontalHeader(), &QHeaderView::sectionClicked, this, [=, this](int logicalIndex) {
        GroupSortAction action;
        if (proxy_last_order == logicalIndex) {
            action.descending = true;
            proxy_last_order = -1;
        } else {
            proxy_last_order = logicalIndex;
        }
        if (logicalIndex == ProfilesTableModel::ColType) {
            auto group = Configs::dataManager->groupsRepo->CurrentGroup();
            action.method = (Configs::dataManager->settingsRepo->show_config_security && group
                             && group->type_sort_by == Configs::typeBy::bySecurity)
                                ? GroupSortMethod::BySecurity
                                : GroupSortMethod::ByType;
        } else if (logicalIndex == ProfilesTableModel::ColAddress) {
            action.method = GroupSortMethod::ByAddress;
        } else if (logicalIndex == ProfilesTableModel::ColName) {
            action.method = GroupSortMethod::ByName;
        } else if (logicalIndex == ProfilesTableModel::ColTestResult) {
            action.method = GroupSortMethod::ByTestResult;
        } else if (logicalIndex == ProfilesTableModel::ColTraffic) {
            action.method = GroupSortMethod::ByTraffic;
        } else {
            return;
        }
        runOnNewThread([=, this] {
            auto currGroup = Configs::dataManager->groupsRepo->CurrentGroup();
            if (currGroup == nullptr) return;
            if (!currGroup->SortProfiles(action)) {
                runOnUiThread([=] {
                    MessageBoxWarning("Action already in progress", "A sort action is already in progress");
                });
                return;
            }
            Configs::dataManager->groupsRepo->Save(currGroup);
            runOnUiThread([=, this] {
                refresh_proxy_list({}, true);
            });
        });
    });
    connect(ui->profilesTableView->horizontalHeader(), &QHeaderView::sectionResized, this, [=, this](int, int, int) {
        auto group = Configs::dataManager->groupsRepo->CurrentGroup();
        if (m_adjustingColumns || Configs::dataManager->settingsRepo->refreshing_group || group == nullptr) return;
        group->column_width.clear();
        for (int i = 0; i < ui->profilesTableView->horizontalHeader()->count(); i++) {
            group->column_width.push_back(ui->profilesTableView->horizontalHeader()->sectionSize(i));
        }
        Configs::dataManager->groupsRepo->Save(Configs::dataManager->groupsRepo->CurrentGroup());
    });
    ui->profilesTableView->horizontalHeader()->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(ui->profilesTableView->horizontalHeader(), &QWidget::customContextMenuRequested, this, [this](const QPoint& pos) {
        auto* header = ui->profilesTableView->horizontalHeader();
        int columnIndex = header->logicalIndexAt(pos);
        auto group = Configs::dataManager->groupsRepo->CurrentGroup();
        if (group == nullptr) return;
        // The column-widths action carries its shortcut id as data, so it must never be read as a sort key.
        auto execWithResetWidths = [&](QMenu& menu) -> QAction* {
            if (!menu.isEmpty()) menu.addSeparator();
            menu.addAction(ui->actionRefresh_Column_Widths);
            auto* chosen = menu.exec(header->mapToGlobal(pos));
            return chosen == ui->actionRefresh_Column_Widths ? nullptr : chosen;
        };
        if (columnIndex == ProfilesTableModel::ColType && Configs::dataManager->settingsRepo->show_config_security) {
            QMenu menu(this);
            auto* sortByLabel = menu.addAction(tr("Sort By:"));
            sortByLabel->setEnabled(false);

            struct TypeSortOption { Configs::typeBy value; QString label; };
            const QList<TypeSortOption> options = {
                { Configs::typeBy::byType, tr("Type") },
                { Configs::typeBy::bySecurity, tr("Security") },
            };
            for (const auto& opt : options) {
                auto* act = menu.addAction(opt.label);
                act->setData(static_cast<int>(opt.value));
                act->setCheckable(true);
                act->setChecked(group->type_sort_by == opt.value);
            }

            auto* chosen = execWithResetWidths(menu);
            if (chosen == nullptr || !chosen->data().isValid()) return;

            group->type_sort_by = static_cast<Configs::typeBy>(chosen->data().toInt());
            Configs::dataManager->groupsRepo->Save(group);
            GroupSortAction action;
            action.method = group->type_sort_by == Configs::typeBy::bySecurity
                                ? GroupSortMethod::BySecurity
                                : GroupSortMethod::ByType;
            runOnNewThread([=, this] {
                auto currGroup = Configs::dataManager->groupsRepo->CurrentGroup();
                if (currGroup == nullptr) return;
                if (!currGroup->SortProfiles(action)) {
                    runOnUiThread([=] {
                        MessageBoxWarning("Action already in progress", "A sort action is already in progress");
                    });
                    return;
                }
                Configs::dataManager->groupsRepo->Save(currGroup);
                runOnUiThread([=, this] {
                    refresh_proxy_list({}, true);
                });
            });
            return;
        }
        if (columnIndex == ProfilesTableModel::ColTestResult) {
            QMenu menu(this);
            auto* includeLabel = menu.addAction(tr("Include:"));
            includeLabel->setEnabled(false);

            auto* actionShowOutIP = menu.addAction(tr("Out IP"));
            actionShowOutIP->setCheckable(true);
            actionShowOutIP->setChecked(group->test_items_to_show == Configs::testShowItems::all ||
                group->test_items_to_show == Configs::testShowItems::ipOnly);

            auto* actionShowSpeed = menu.addAction(tr("Speed"));
            actionShowSpeed->setCheckable(true);
            actionShowSpeed->setChecked(group->test_items_to_show == Configs::testShowItems::all ||
                group->test_items_to_show == Configs::testShowItems::speedOnly);

            auto updateTestItemsToShow = [this, group, actionShowOutIP, actionShowSpeed] {
                    const bool ip = actionShowOutIP->isChecked();
                    const bool speed = actionShowSpeed->isChecked();
                    if (ip && speed) group->test_items_to_show = Configs::testShowItems::all;
                    else if (ip) group->test_items_to_show = Configs::testShowItems::ipOnly;
                    else if (speed) group->test_items_to_show = Configs::testShowItems::speedOnly;
                    else group->test_items_to_show = Configs::testShowItems::none;
                    Configs::dataManager->groupsRepo->Save(group);
                    if (group->calculated_column_width.size() > ProfilesTableModel::ColTestResult) {
                        group->calculated_column_width[ProfilesTableModel::ColTestResult] = 0;
                    }
                    refresh_proxy_list();
                };

            connect(actionShowOutIP, &QAction::triggered, this, updateTestItemsToShow);
            connect(actionShowSpeed, &QAction::triggered, this, updateTestItemsToShow);

            menu.addSeparator();
            auto* sortByLabel = menu.addAction(tr("Sort By:"));
            sortByLabel->setEnabled(false);

            struct SortOption { int value; QString label; };
            QList<SortOption> options = {
                { static_cast<int>(Configs::testBy::latency), tr("Latency") },
                { static_cast<int>(Configs::testBy::dlSpeed), tr("Download Speed") },
                { static_cast<int>(Configs::testBy::ulSpeed), tr("Upload Speed") },
                { static_cast<int>(Configs::testBy::ipOut), tr("IP Out") }
            };
            for (const auto& opt : options) {
                auto* act = menu.addAction(opt.label);
                act->setData(opt.value);
                act->setCheckable(true);
                act->setChecked(static_cast<int>(group->test_sort_by) == opt.value);
            }

            auto* chosen = execWithResetWidths(menu);
            if (chosen == nullptr || !chosen->data().isValid()) return;

            int testSortBy = chosen->data().toInt();
            group->test_sort_by = static_cast<Configs::testBy>(testSortBy);
            Configs::dataManager->groupsRepo->Save(group);
            GroupSortAction action;
            action.method = GroupSortMethod::ByTestResult;
            action.descending = true;
            runOnNewThread([=, this] {
                auto currGroup = Configs::dataManager->groupsRepo->CurrentGroup();
                if (currGroup == nullptr) return;
                if (!currGroup->SortProfiles(action)) {
                    runOnUiThread([=] {
                        MessageBoxWarning("Action already in progress", "A sort action is already in progress");
                        });
                    return;
                }
                Configs::dataManager->groupsRepo->Save(currGroup);
                runOnUiThread([=, this] {
                    refresh_proxy_list({}, true);
                    });
                });
            return;
        }
        if (columnIndex == ProfilesTableModel::ColTraffic) {
            QMenu menu(this);
            auto* sortByLabel = menu.addAction(tr("Sort By:"));
            sortByLabel->setEnabled(false);

            struct TrafficSortOption { int value; QString label; };
            QList<TrafficSortOption> options = {
                { 0, tr("Total") },
                { 1, tr("Downloaded") },
                { 2, tr("Uploaded") }
            };

            for (const auto& opt : options) {
                auto* act = menu.addAction(opt.label);
                act->setData(opt.value);
                act->setCheckable(true);
                act->setChecked(static_cast<int>(group->traffic_sort_by) == opt.value);
            }

            auto* chosen = execWithResetWidths(menu);
            if (chosen == nullptr || !chosen->data().isValid()) return;

            int trafficSortBy = chosen->data().toInt();
            group->traffic_sort_by = static_cast<Configs::trafficBy>(trafficSortBy);
            Configs::dataManager->groupsRepo->Save(group);
            GroupSortAction action;
            action.method = GroupSortMethod::ByTraffic;
            action.descending = false;
            runOnNewThread([=, this] {
                auto currGroup = Configs::dataManager->groupsRepo->CurrentGroup();
                if (currGroup == nullptr) return;
                if (!currGroup->SortProfiles(action)) {
                    runOnUiThread([=] {
                        MessageBoxWarning("Action already in progress", "A sort action is already in progress");
                        });
                    return;
                }
                Configs::dataManager->groupsRepo->Save(Configs::dataManager->groupsRepo->CurrentGroup());
                runOnUiThread([=, this] {
                    refresh_proxy_list();
                    });
                });
            return;
        }
        QMenu menu(this);
        execWithResetWidths(menu);
    });
    ui->profilesTableView->verticalHeader()->setStretchLastSection(false);
    ui->profilesTableView->verticalHeader()->setDefaultSectionSize(24);
    ui->profilesTableView->verticalHeader()->setSectionResizeMode(QHeaderView::Fixed);
    ui->profilesTableView->setTabKeyNavigation(false);
    ui->profilesTableView->horizontalHeader()->setResizeContentsPrecision(0);

    connect(ui->profilesTableView->verticalScrollBar(), &QScrollBar::valueChanged, ui->profilesTableView, [=, this] {
        if (!ui->profilesTableView->isVisible()) return;
        refresh_proxy_list_column_size();
    });
    ui->profilesTableView->viewport()->installEventFilter(this);

    auto *filterHeader = static_cast<ProfilesTableFilterHeader*>(ui->profilesTableView->horizontalHeader());
    filterHeader->setLastFilterColumn(Configs::dataManager->settingsRepo->last_filter_column);
    connect(filterHeader, &ProfilesTableFilterHeader::lastFilterColumnChanged, this, [](int column)
    {
        Configs::dataManager->settingsRepo->last_filter_column = column;
        Configs::dataManager->settingsRepo->Save();
    });

    m_filterRefreshDebounce = new QTimer(this);
    m_filterRefreshDebounce->setSingleShot(true);
    m_filterRefreshDebounce->setInterval(50);
    connect(m_filterRefreshDebounce, &QTimer::timeout, this, [this] { applyProfileFilters(); });

    connect(filterHeader, &ProfilesTableFilterHeader::typeFilterChanged, this, [this](const QString& currentText)
    {
       typeFilterString = currentText;
       m_filterRefreshDebounce->start();
    });
    connect(filterHeader, &ProfilesTableFilterHeader::addressFilterChanged, this, [this](const QString& currentText)
    {
       addressFilterString = currentText;
       m_filterRefreshDebounce->start();
    });
    connect(filterHeader, &ProfilesTableFilterHeader::nameFilterChanged, this, [this](const QString& currentText)
    {
       nameFilterString = currentText;
       m_filterRefreshDebounce->start();
    });
    connect(filterHeader, &ProfilesTableFilterHeader::testFilterChanged, this, [this](const QString& currentText)
    {
       countryFilterString = currentText;
       m_filterRefreshDebounce->start();
    });
    connect(filterHeader, &ProfilesTableFilterHeader::focusTableRequested, this,
            [this](bool selectFirst) { focusProfilesTable(selectFirst); });

    connect(Subscription::updater(), &Subscription::GroupUpdater::asyncUpdateCallback, this, [this](int gid) {
        if (gid >= 0) updateTabToolTip(gid);
    });

    this->refresh_groups();

    tray = new TrayIcon(this);
    tray->setIcon(Icon::GetTrayIcon(Icon::TrayIconStatus::None));
    QApplication::setWindowIcon(Icon::GetTaskbarIcon(Icon::TrayIconStatus::None));
    trayMenu = new QMenu();
    trayMenu->addAction(ui->actionShow_window);
    trayMenu->addSeparator();

    trayConnectAction = new QAction(tr("Connect"), trayMenu);
    connect(trayConnectAction, &QAction::triggered, this, [this]() {
        if (running != nullptr) profile_stop(false, false, true);
        else profile_start();
    });
    trayMenu->addAction(trayConnectAction);
    trayMenu->addSeparator();

    trayMenu->addAction(ui->actionStart_with_system);
    trayMenu->addAction(ui->actionRemember_last_proxy);
    trayMenu->addAction(ui->actionAllow_LAN);
    trayMenu->addSeparator();

    auto *actSelectServer = new QAction(tr("Select Profile"), trayMenu);
    connect(actSelectServer, &QAction::triggered, this, [this]() { openTraySelector(false); });
    trayMenu->addAction(actSelectServer);
    auto *actSelectRouting = new QAction(tr("Select Routing"), trayMenu);
    connect(actSelectRouting, &QAction::triggered, this, [this]() { openTraySelector(true); });
    trayMenu->addAction(actSelectRouting);
    auto *actOtpCodes = new QAction(tr("OTP Codes"), trayMenu);
    connect(actOtpCodes, &QAction::triggered, this, [this]() { openTrayOtpCodes(); });
    trayMenu->addAction(actOtpCodes);
    // MacOS cannot reuse menus across different parents properly
    if (getOS() == Darwin) {
        auto* traySpmodeMenu = new QMenu(ui->menu_spmode->title(), trayMenu);
        traySpmodeMenu->addAction(ui->menu_spmode_system_proxy);
        traySpmodeMenu->addAction(ui->menu_spmode_vpn);
        connect(traySpmodeMenu, &QMenu::aboutToShow, this, [=,this]() {
            ui->menu_spmode_disabled->setChecked(!(Configs::dataManager->settingsRepo->spmode_system_proxy || Configs::dataManager->settingsRepo->spmode_vpn));
            ui->menu_spmode_system_proxy->setChecked(Configs::dataManager->settingsRepo->spmode_system_proxy);
            ui->menu_spmode_vpn->setChecked(Configs::dataManager->settingsRepo->spmode_vpn);
        });
        trayMenu->addMenu(traySpmodeMenu);
    } else {
        trayMenu->addMenu(ui->menu_spmode);
    }
    trayMenu->addSeparator();

    trayMenu->addAction(ui->actionRestart_Proxy);
    trayMenu->addAction(ui->actionRestart_Program);
    trayMenu->addAction(ui->menu_exit);
    tray->setVisible(!Configs::dataManager->settingsRepo->disable_tray);
    tray->setContextMenu(trayMenu);
    connect(tray, &TrayIcon::activated, qApp, [=, this](QSystemTrayIcon::ActivationReason reason) {
        if (reason == QSystemTrayIcon::Trigger && getOS() != Darwin) {
            trayClickEvent();
        }
    });

    ui->actionRemember_last_proxy->setChecked(Configs::dataManager->settingsRepo->remember_enable);
    ui->actionStart_with_system->setChecked(AutoRun_IsEnabled());
    ui->actionAllow_LAN->setChecked(QStringList{"::", "0.0.0.0"}.contains(Configs::dataManager->settingsRepo->inbound_address));

    connect(ui->actionHide_window, &QAction::triggered, this, [=, this](){ HideWindow(this); });
    connect(ui->menu_open_config_folder, &QAction::triggered, this, [=,this] { QDesktopServices::openUrl(QUrl::fromLocalFile(QDir::currentPath())); });
    connect(ui->menu_open_dashboard, &QAction::triggered, this, [=,this] { OpenDashboard(); });
    connect(ui->actionRestart_Proxy, &QAction::triggered, this, [=,this] { RestartCore(); });
    connect(ui->actionRestart_Program, &QAction::triggered, this, [=,this] { MW_dialog_message(MwMessage::RestartProgram, {}); });
    connect(ui->actionShow_window, &QAction::triggered, this, [=,this] { ActivateWindow(this); });
    connect(ui->actionRemember_last_proxy, &QAction::triggered, this, [=,this](bool checked) {
        Configs::dataManager->settingsRepo->remember_enable = checked;
        ui->actionRemember_last_proxy->setChecked(checked);
        Configs::dataManager->settingsRepo->Save();
    });
    connect(ui->actionStart_with_system, &QAction::triggered, this, [=,this](bool checked) {
        if (QString error; !AutoRun_SetEnabled(checked, &error)) {
            MessageBoxWarning(tr("Start with system"), tr("Could not update the autostart entry:") + "\n" + error);
        }
        ui->actionStart_with_system->setChecked(AutoRun_IsEnabled());
    });
    connect(ui->actionAllow_LAN, &QAction::triggered, this, [=,this](bool checked) {
        Configs::dataManager->settingsRepo->inbound_address = checked ? "::" : "127.0.0.1";
        ui->actionAllow_LAN->setChecked(checked);
        MW_dialog_message(MwMessage::UpdateSettings, {});
    });
    connect(ui->checkBox_VPN, &QCheckBox::clicked, this, [=,this](bool checked) { set_spmode_vpn(checked); });
    connect(ui->checkBox_SystemProxy, &QCheckBox::clicked, this, [=,this](bool checked) { set_spmode_system_proxy(checked); });
    connect(ui->menu_spmode, &QMenu::aboutToShow, this, [=,this]() {
        ui->menu_spmode_disabled->setChecked(!(Configs::dataManager->settingsRepo->spmode_system_proxy || Configs::dataManager->settingsRepo->spmode_vpn));
        ui->menu_spmode_system_proxy->setChecked(Configs::dataManager->settingsRepo->spmode_system_proxy);
        ui->menu_spmode_vpn->setChecked(Configs::dataManager->settingsRepo->spmode_vpn);
    });
    connect(ui->menu_spmode_system_proxy, &QAction::triggered, this, [=,this](bool checked) { set_spmode_system_proxy(checked); });
    connect(ui->menu_spmode_vpn, &QAction::triggered, this, [=,this](bool checked) { set_spmode_vpn(checked); });
    connect(ui->menu_spmode_disabled, &QAction::triggered, this, [=,this]() {
        set_spmode_system_proxy(false);
        set_spmode_vpn(false);
    });
    connect(ui->menu_qr, &QAction::triggered, this, [=,this]() { display_qr_link(false); });

    connect(ui->menu_server, &QMenu::aboutToShow, this, [=,this](){
        if (auto selected = get_now_selected_list(); selected.empty())
        {
            ui->actionSpeedtest_Selected->setEnabled(false);
            ui->actionUrl_Test_Selected->setEnabled(false);
            ui->menu_resolve_selected->setEnabled(false);
            ui->actionResolve_Selected_Out_IP->setEnabled(false);
        } else
        {
            ui->actionSpeedtest_Selected->setEnabled(true);
            ui->actionUrl_Test_Selected->setEnabled(true);
            ui->menu_resolve_selected->setEnabled(true);
            ui->actionResolve_Selected_Out_IP->setEnabled(true);
        }
        if (testRunner->isRunning()) {
            ui->menu_server->addAction(ui->menu_stop_testing);
        } else {
            ui->menu_server->removeAction(ui->menu_stop_testing);
        }
    });

    connect(ui->menuTesting, &QMenu::aboutToShow, this, [=,this](){
        ui->actionDelete_Group->setEnabled(Configs::dataManager->groupsRepo->GetAllGroupIds().size() > 1);
        if (testRunner->isRunning()) {
            ui->menuTesting->addAction(ui->menu_stop_testing);
        } else {
            ui->menuTesting->removeAction(ui->menu_stop_testing);
        }
    });

    connect(ui->menuTools, &QMenu::aboutToShow, this, [=,this](){
        ui->actionSpeedtest_Current->setEnabled(running != nullptr);
    });

    connect(ui->actionAdd_New_Group, &QAction::triggered, this, [=,this]{
        auto ent = Configs::dataManager->groupsRepo->NewGroup();
        auto dialog = new DialogEditGroup(ent, this);
        int ret = dialog->exec();
        dialog->deleteLater();

        if (ret == QDialog::Accepted) {
            Configs::dataManager->groupsRepo->AddGroup(ent);
            MW_dialog_message(MwMessage::GroupsChanged, {});
        }
    });

    connect(ui->actionEdit_Group, &QAction::triggered, this, [=,this]{
        auto ent = Configs::dataManager->groupsRepo->CurrentGroup();
        auto dialog = new DialogEditGroup(ent, this);
        connect(dialog, &QDialog::finished, this, [=,this] {
            if (dialog->result() == QDialog::Accepted) {
                Configs::dataManager->groupsRepo->Save(ent);
                MW_dialog_message(MwMessage::GroupsChanged, dialog->RestartNeeded() ? QStringList{MwArg::RestartProxy} : QStringList{});
            }
            dialog->deleteLater();
        });
        dialog->show();
    });

    connect(ui->actionDelete_Group, &QAction::triggered, this, [=,this]{
        if (Configs::dataManager->groupsRepo->GetAllGroupIds().size() <= 1) return;
        auto id = Configs::dataManager->groupsRepo->CurrentGroup()->id;
        if (QMessageBox::question(this, tr("Confirmation"), tr("Remove %1?").arg(Configs::dataManager->groupsRepo->GetGroup(id)->name)) ==
            QMessageBox::StandardButton::Yes) {
            if (running != nullptr) {
                if (running->gid == id) profile_stop(false, true, false);
            }
            Configs::dataManager->groupsRepo->DeleteGroup(id);
            MW_dialog_message(MwMessage::GroupsChanged, {});
        }
    });

    connect(ui->actionUpdate_All_Subscriptions, &QAction::triggered, this, [=,this]{
        if (QMessageBox::question(this, tr("Confirmation"), tr("Update all subscriptions?")) == QMessageBox::StandardButton::Yes) {
            Subscription::updater()->RefreshAll();
        }
    });

    connect(ui->actionRefresh_Column_Widths, &QAction::triggered, this, [=, this] {
        auto ent = Configs::dataManager->groupsRepo->CurrentGroup();
        if (ent == nullptr) return;
        ent->column_width.clear();
        Configs::dataManager->groupsRepo->Save(ent);
        show_group(ent->id);
    });

    connect(ui->menuRouting_Menu, &QMenu::aboutToShow, this, [=,this]()
    {
        ui->menuRouting_Menu->clear();
        ui->menuRouting_Menu->addAction(ui->menu_routing_settings);

        auto* actionAdblock = new QAction(ui->menuRouting_Menu);
        actionAdblock->setText(tr("Enable AdBlock"));
        actionAdblock->setCheckable(true);
        actionAdblock->setChecked(Configs::dataManager->settingsRepo->adblock_enable);
        connect(actionAdblock, &QAction::triggered, this, [=,this](bool checked) {
            Configs::dataManager->settingsRepo->adblock_enable = checked;
            actionAdblock->setChecked(checked);
            Configs::dataManager->settingsRepo->Save();
            if (Configs::dataManager->settingsRepo->started_id >= 0) profile_start(Configs::dataManager->settingsRepo->started_id);
        });
        ui->menuRouting_Menu->addAction(actionAdblock);

        auto* actionWarp = new QAction(ui->menuRouting_Menu);
        actionWarp->setText(tr("Enable Warp"));
        actionWarp->setCheckable(true);
        actionWarp->setChecked(Configs::dataManager->settingsRepo->enable_warp);
        connect(actionWarp, &QAction::triggered, this, [=,this](bool checked) {
            Configs::dataManager->settingsRepo->enable_warp = checked;
            actionWarp->setChecked(checked);
            Configs::dataManager->settingsRepo->Save();
            if (Configs::dataManager->settingsRepo->started_id >= 0) profile_start(Configs::dataManager->settingsRepo->started_id);
        });
        ui->menuRouting_Menu->addAction(actionWarp);

        QMenu* profilesMenu = ui->menuRouting_Menu->addMenu(QObject::tr("Download Profiles"));
        for (const QString &country : QStringList{"China", "Iran", "Russia"})
        {
            auto* action = new QAction(profilesMenu);
            action->setText(country);
            connect(action, &QAction::triggered, this, [=,this]()
            {
                auto resp = NetworkRequestHelper::HttpGet(Configs::get_jsdelivr_link("https://raw.githubusercontent.com/throneproj/routeprofiles/profile/Profile_" + country));
                if (!resp.error.isEmpty()) {
                    runOnUiThread([=] {
                        MessageBoxWarning(QObject::tr("Download Profiles"), QObject::tr("Requesting profile error: %1").arg(resp.error + "\n" + resp.data));
                    });
                    return;
                }
                handle_add_remote_routes(resp.data);
            });
            profilesMenu->addAction(action);
        }

        ui->actionUpdate_Rule_Sets->setEnabled(running != nullptr && !m_ruleSetUpdateBusy);
        ui->menuRouting_Menu->addAction(ui->actionUpdate_Rule_Sets);

        ui->menuRouting_Menu->addSeparator();
        for (const auto& route : Configs::dataManager->routesRepo->GetAllRouteProfiles())
        {
            auto* action = new QAction(ui->menuRouting_Menu);
            action->setText(route->name);
            action->setData(route->id);
            action->setCheckable(true);
            action->setChecked(Configs::dataManager->settingsRepo->current_route_id == route->id);
            connect(action, &QAction::triggered, this, [=,this]() { choose_route(action->data().toInt()); });
            ui->menuRouting_Menu->addAction(action);
        }
    });
    connect(ui->actionClear_Test_Result, &QAction::triggered, this, [=, this]() { clear_test_results(get_now_selected_list()); });
    connect(ui->actionUrl_Test_Selected, &QAction::triggered, this, [=,this]() {
        testRunner->runUrlTests(get_now_selected_list());
    });
    connect(ui->actionUrl_Test_Group, &QAction::triggered, this, [=,this]() {
        testRunner->runUrlTests(Configs::dataManager->groupsRepo->CurrentGroup()->Profiles());
    });
    connect(ui->actionSpeedtest_Current, &QAction::triggered, this, [=,this]()
    {
        if (running != nullptr)
        {
            testRunner->runSpeedTests({}, true);
        }
    });
    connect(ui->actionSpeedtest_Selected, &QAction::triggered, this, [=,this]()
    {
        testRunner->runSpeedTests(get_now_selected_list());
    });
    connect(ui->actionSpeedtest_Group, &QAction::triggered, this, [=,this]()
    {
        testRunner->runSpeedTests(Configs::dataManager->groupsRepo->CurrentGroup()->Profiles());
    });
    connect(ui->actionResolve_Selected_Out_IP, &QAction::triggered, this, [=,this]() {
        testRunner->runIpTests(get_now_selected_list());
    });
    connect(ui->actionResolve_Out_IP, &QAction::triggered, this, [=,this]() {
        testRunner->runIpTests(Configs::dataManager->groupsRepo->CurrentGroup()->Profiles());
    });
    connect(ui->menu_stop_testing, &QAction::triggered, this, [=,this]() { testRunner->stop(); });
    auto set_selected_or_group = [=,this](int mode) {
        // 0=group 1=select 2=unknown(menu is hide)
        ui->menu_server->setProperty("selected_or_group", mode);
    };
    connect(ui->menu_server, &QMenu::aboutToHide, this, [=,this] {
        setTimeout([=,this] { set_selected_or_group(2); }, this, 200);
    });
    set_selected_or_group(2);
    connect(ui->menu_share_item, &QMenu::aboutToShow, this, [=,this] {
        QString name;
        auto selected = get_now_selected_list();

        ui->menu_export_config->setVisible(false);
        ui->actionExport_Xray_config->setVisible(false);
        if (selected.isEmpty()) return;

        auto profile = Configs::dataManager->profilesRepo->GetProfile(selected.first());
        if (!profile) return;

        if (selected.count() == 1 && profile->DisplayTestResult().trimmed().isEmpty()) {
            ui->actionCopy_Test_Result->setVisible(false);
        } else {
            ui->actionCopy_Test_Result->setVisible(true);
        }

        ui->menu_export_config->setVisible(true);
        if (profile->outbound->IsXray() || profile->type == "chain") ui->actionExport_Xray_config->setVisible(true);
    });
    connect(ui->actionExport_Xray_config, &QAction::triggered, this, [=,this]() {
        auto ents = get_now_selected_list();
        if (ents.count() != 1) return;
        auto ent = Configs::dataManager->profilesRepo->GetProfile(ents.first());

        auto result = Configs::BuildSingBoxConfig(ent);
        if (!result->error.isEmpty()) {
            MessageBoxWarning("Build config error", result->error);
            return;
        }
        QString config_core = QJsonObject2QString(result->xrayConfig, true);
        QApplication::clipboard()->setText(config_core);

        QMessageBox msg(QMessageBox::Information, tr("Config copied"), config_core);
        QPushButton *button_1 = msg.addButton(tr("Copy core config"), QMessageBox::YesRole);
        QPushButton *button_2 = msg.addButton(tr("Copy test config"), QMessageBox::YesRole);
        msg.addButton(QMessageBox::Ok);
        msg.setEscapeButton(QMessageBox::Ok);
        msg.setDefaultButton(QMessageBox::Ok);
        msg.exec();
        if (msg.clickedButton() == button_1) {
            QApplication::clipboard()->setText(config_core);
        } else if (msg.clickedButton() == button_2) {
            auto res = Configs::BuildTestConfig({ent});
            if (!res->error.isEmpty()) {
                MessageBoxWarning("Build Test config error", res->error);
                return;
            }
            config_core = QJsonObject2QString(res->xrayConfig, true);
            QApplication::clipboard()->setText(config_core);
        }
    });
    connect(ui->actionCopy_Test_Result, &QAction::triggered, this, [=,this]() {
        auto ents = get_now_selected_list();
        if (ents.count() == 0 || ents.count() > 1000) return;
        auto entList = Configs::dataManager->profilesRepo->GetProfileBatch(ents);
        QString res;
        int counter = 0;
        for (auto ent : entList) {
            auto testRes = ent->DisplayTestResult();
            if (!testRes.trimmed().isEmpty()) {
                res += testRes.trimmed() + "\n";
                counter++;
            }
        }
        QApplication::clipboard()->setText(res);
        MW_show_log(QString::number(counter) + tr(" Test result(s) copied to clipboard!"));
    });
    connect(ui->actionAdd_profile_from_File, &QAction::triggered, this, [=,this]()
    {
        // QFileDialog defaults to the first filter; config files routinely carry no extension.
        const auto filters = QStringList{
            tr("All files (*)"),
            tr("Config files (*.json *.conf *.txt *.yaml *.yml *.ini *.ovpn *.xml)"),
            tr("QR code images (*.png *.jpg *.jpeg *.bmp *.gif *.webp)"),
        };
        const auto paths = QFileDialog::getOpenFileNames(this, tr("Select profile files"), QString(), filters.join(";;"));
        if (paths.isEmpty()) return;
        importFromFiles(paths);
    });

    connect(qApp, &QGuiApplication::commitDataRequest, this, &MainWindow::on_commitDataRequest);

    auto t = new QTimer;
    connect(t, &QTimer::timeout, this, [=,this]() { refresh_status(); });
    t->start(2000);

    t = new QTimer;
    connect(t, &QTimer::timeout, this, [&] { Configs_sys::logCounter.fetchAndStoreRelaxed(0); });
    t->start(1000);

    m_proxyListRefreshDebounce = new QTimer(this);
    m_proxyListRefreshDebounce->setSingleShot(true);
    connect(m_proxyListRefreshDebounce, &QTimer::timeout, this, [this] { refresh_proxy_list({}, false); });

    // The selector monitor emits from its own poll thread.
    connect(Stats::autoSelectorMonitor, &Stats::AutoSelectorMonitor::poolExhausted, this,
            [this](int profileID) { on_auto_selector_exhausted(profileID); }, Qt::QueuedConnection);
    connect(Stats::autoSelectorMonitor, &Stats::AutoSelectorMonitor::updated, this,
            [this] { refresh_auto_selector_view(); }, Qt::QueuedConnection);

    auto* scanManager = Scanner::ScanManager::instance();
    connect(scanManager, &Scanner::ScanManager::progressChanged, this,
            [this](int) { refreshScannerDataView(); }, Qt::QueuedConnection);
    connect(scanManager, &Scanner::ScanManager::statusChanged, this,
            [this](int) { refreshScannerDataView(true); }, Qt::QueuedConnection);
    connect(scanManager, &Scanner::ScanManager::scansChanged, this, [this] {
        m_scannerNames.clear();
        refreshScannerDataView(true);
    }, Qt::QueuedConnection);

    {
        auto* runner = Throne::PeriodicRunner::instance();
        // Interval is sign-encoded in settings (negative = disabled); < 30 min counts as off.
        const auto minutesOf = [](int v) { return v >= 30 ? v : 0; };
        // Every poll while enabled: each group keeps its own schedule, persisted as its sub_last_update.
        runner->Add({
            {},
            [minutesOf] { return minutesOf(Configs::dataManager->settingsRepo->sub_auto_update) > 0 ? 1 : 0; },
            nullptr,
            nullptr,
            [] { Subscription::updater()->CheckAutoUpdate(); },
        });
        runner->Add({
            tr("routing profiles"),
            [minutesOf] { return minutesOf(Configs::dataManager->settingsRepo->route_auto_update); },
            [] { return Configs::dataManager->settingsRepo->route_auto_update_last; },
            [](qint64 t) {
                Configs::dataManager->settingsRepo->route_auto_update_last = t;
                Configs::dataManager->settingsRepo->Save();
            },
            [] { UI_update_all_remote_routes(true); },
        });
        runner->Add({
            {},
            [] { return 1; },
            nullptr,
            nullptr,
            [] { Scanner::IpListUpdater::instance()->CheckAutoUpdate(); },
        });
    }

    if (!Configs::dataManager->settingsRepo->flag_tray) show();
    else if (tray->isVisible()) HideWindow(this);

    // Connected only now: the handler refreshes the tray and views, which do not exist when apply() first runs above.
    connect(Sys::KillSwitch::instance(), &Sys::KillSwitch::stateChanged, this, [this] { kill_switch_state_changed(); });
    QTimer::singleShot(0, this, [this] { kill_switch_state_changed(); });

    ui->data_view->setStyleSheet("background: transparent; border: none;");

    // Wrapped notices must retain access to Restart/Dismiss even in a narrow window.
    ui->data_view->setVerticalScrollBarPolicy(Qt::ScrollBarAsNeeded);
    connect(ui->data_view->document()->documentLayout(), &QAbstractTextDocumentLayout::documentSizeChanged,
            this, [this](const QSizeF &size) {
                ui->data_view->setMinimumHeight(qBound(60, qCeil(size.height()) + 8, 150));
            });
    ui->data_view->setOpenLinks(false);
    ui->data_view->setOpenExternalLinks(false);
    connect(ui->data_view, &QTextBrowser::anchorClicked, this, [this](const QUrl& url) {
        const auto action = url.toString();
        if (action == QLatin1String(DataViewHtmlGenerator::RestartActionUrl)) {
            const int startedID = Configs::dataManager->settingsRepo->started_id;
            clearRestartNeeded();
            if (startedID >= 0) profile_start(startedID);
        } else if (action == QLatin1String(DataViewHtmlGenerator::DismissRestartActionUrl)) {
            clearRestartNeeded();
        }
    });

    const QPointer<RemoteApi::Router> apiRouter = new RemoteApi::Router(this);
    RemoteApi::Server::instance()->setDispatcher([apiRouter](const RemoteApi::Request &request, const RemoteApi::Responder &respond) {
        if (apiRouter) apiRouter->dispatch(request, respond);
        else respond(RemoteApi::Response::Error(503, "exiting", "Throne is shutting down"));
    });
    RemoteApi::Server::instance()->apply(RemoteApi::ConfigFromSettings());
#ifdef ONLINESETI_EDITION
    setupOnlineSetiDashboard();
#endif
}

#ifdef ONLINESETI_EDITION
void MainWindow::showOnlineSetiPage(int index) {
    if (!onlineSetiPages || index < 0 || index >= onlineSetiPages->count()) return;
    onlineSetiPages->setCurrentIndex(index);
    for (int i = 0; i < onlineSetiNavButtons.size(); ++i) {
        onlineSetiNavButtons[i]->setProperty("active", i == index);
        onlineSetiNavButtons[i]->style()->unpolish(onlineSetiNavButtons[i]);
        onlineSetiNavButtons[i]->style()->polish(onlineSetiNavButtons[i]);
    }
    if (index == 0) refreshOnlineSetiDashboard();
}

void MainWindow::setupOnlineSetiDashboard() {
    QWidget *legacyPage = ui->centralwidget;
    legacyPage->setParent(nullptr);

    auto *shell = new QWidget(this);
    shell->setObjectName("onlineSetiShell");
    auto *shellLayout = new QHBoxLayout(shell);
    shellLayout->setContentsMargins(0, 0, 0, 0);
    shellLayout->setSpacing(0);

    auto *rail = new QWidget(shell);
    rail->setObjectName("onlineSetiRail");
    rail->setMinimumWidth(224);
    rail->setMaximumWidth(224);
    auto *railLayout = new QVBoxLayout(rail);
    railLayout->setContentsMargins(14, 22, 14, 16);
    railLayout->setSpacing(8);

    auto *brand = new QWidget(rail);
    brand->setObjectName("onlineSetiBrand");
    auto *brandLayout = new QHBoxLayout(brand);
    brandLayout->setContentsMargins(8, 0, 4, 24);
    brandLayout->setSpacing(11);
    auto *mark = new QLabel(brand);
    mark->setObjectName("onlineSetiBrandMark");
    mark->setAlignment(Qt::AlignCenter);
    mark->setFixedSize(40, 40);
    mark->setPixmap(QPixmap(QStringLiteral(":/Throne/OnlineSeti.png")).scaled(
        40, 40, Qt::KeepAspectRatio, Qt::SmoothTransformation));
    auto *brandText = new QLabel(QStringLiteral("OnlineSeti VPN"), brand);
    brandText->setObjectName("onlineSetiBrandText");
    brandText->setWordWrap(true);
    brandLayout->addWidget(mark);
    brandLayout->addWidget(brandText, 1);
    railLayout->addWidget(brand);

    auto *contentHost = new QWidget(shell);
    contentHost->setObjectName("onlineSetiContentHost");
    auto *contentHostLayout = new QHBoxLayout(contentHost);
    contentHostLayout->setContentsMargins(0, 0, 0, 0);
    contentHostLayout->setSpacing(0);
    contentHostLayout->addStretch();
    onlineSetiPages = new QStackedWidget(contentHost);
    onlineSetiPages->setObjectName("onlineSetiPages");
    onlineSetiPages->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);
    onlineSetiPages->setMaximumWidth(1480);
    contentHostLayout->addWidget(onlineSetiPages, 1);
    contentHostLayout->addStretch();
    onlineSetiHome = new QWidget(onlineSetiPages);
    onlineSetiHome->setObjectName("onlineSetiHomePage");
    auto *homeOuter = new QVBoxLayout(onlineSetiHome);
    homeOuter->setContentsMargins(30, 26, 30, 28);
    homeOuter->setSpacing(18);

    auto *homeHeader = new QHBoxLayout;
    auto *headingStack = new QVBoxLayout;
    auto *eyebrow = new QLabel(tr("CONTROL PANEL"), onlineSetiHome);
    eyebrow->setObjectName("onlineSetiEyebrow");
    auto *heading = new QLabel(tr("VPN connection"), onlineSetiHome);
    heading->setObjectName("onlineSetiPageHeading");
    headingStack->addWidget(eyebrow);
    headingStack->addWidget(heading);
    homeHeader->addLayout(headingStack);
    homeHeader->addStretch();
    auto *openServers = new QPushButton(tr("Choose server  ›"), onlineSetiHome);
    openServers->setObjectName("onlineSetiSecondaryButton");
    homeHeader->addWidget(openServers);
    homeOuter->addLayout(homeHeader);

    auto *cards = new QGridLayout;
    cards->setHorizontalSpacing(16);
    cards->setVerticalSpacing(16);
    cards->setColumnStretch(0, 13);
    cards->setColumnStretch(1, 9);

    auto *connectCard = new QFrame(onlineSetiHome);
    connectCard->setObjectName("onlineSetiConnectCard");
    auto *connectLayout = new QVBoxLayout(connectCard);
    connectLayout->setContentsMargins(24, 22, 24, 22);
    connectLayout->setSpacing(10);
    auto *connectTop = new QHBoxLayout;
    onlineSetiConnectionStatus = new QLabel(connectCard);
    onlineSetiConnectionStatus->setObjectName("onlineSetiStatusPill");
    connectTop->addWidget(onlineSetiConnectionStatus, 0, Qt::AlignLeft);
    connectTop->addStretch();
    connectLayout->addLayout(connectTop);
    connectLayout->addStretch();
    onlineSetiPowerButton = new QPushButton(QStringLiteral("⏻"), connectCard);
    onlineSetiPowerButton->setObjectName("onlineSetiPowerButton");
    onlineSetiPowerButton->setFixedSize(108, 108);
    onlineSetiPowerButton->setCursor(Qt::PointingHandCursor);
    connectCard->setMinimumHeight(287);
    connectCard->setMaximumHeight(330);
    connectLayout->addWidget(onlineSetiPowerButton, 0, Qt::AlignHCenter);
    onlineSetiConnectionDetail = new QLabel(connectCard);
    onlineSetiConnectionDetail->setObjectName("onlineSetiConnectDetail");
    onlineSetiConnectionDetail->setAlignment(Qt::AlignCenter);
    onlineSetiConnectionDetail->setWordWrap(true);
    connectLayout->addWidget(onlineSetiConnectionDetail);
    auto *helper = new QLabel(tr("Traffic is routed through the selected profile"), connectCard);
    helper->setObjectName("onlineSetiMutedText");
    helper->setAlignment(Qt::AlignCenter);
    connectLayout->addWidget(helper);
    connectLayout->addStretch();
    cards->addWidget(connectCard, 0, 0, 2, 1);

    auto makeCard = [this](const QString &title, QLabel **value, QLabel **meta) {
        auto *card = new QFrame(onlineSetiHome);
        card->setObjectName("onlineSetiInfoCard");
        auto *layout = new QVBoxLayout(card);
        layout->setContentsMargins(19, 17, 19, 17);
        layout->setSpacing(7);
        auto *head = new QLabel(title, card);
        head->setObjectName("onlineSetiCardTitle");
        *value = new QLabel(card);
        (*value)->setObjectName("onlineSetiCardValue");
        (*value)->setWordWrap(true);
        *meta = new QLabel(card);
        (*meta)->setObjectName("onlineSetiMutedText");
        (*meta)->setWordWrap(true);
        layout->addWidget(head);
        layout->addWidget(*value);
        layout->addWidget(*meta);
        layout->addStretch();
        return card;
    };
    auto *profileCard = makeCard(tr("ACTIVE PROFILE"), &onlineSetiProfileName, &onlineSetiProfileMeta);
    profileCard->setMinimumHeight(132);
    profileCard->setMaximumHeight(157);
    cards->addWidget(profileCard, 0, 1);
    auto *trafficCard = makeCard(tr("TRAFFIC"), &onlineSetiTraffic, &onlineSetiRoute);
    trafficCard->setMinimumHeight(132);
    trafficCard->setMaximumHeight(157);
    cards->addWidget(trafficCard, 1, 1);

    auto *routeCard = new QFrame(onlineSetiHome);
    routeCard->setObjectName("onlineSetiRouteCard");
    auto *routeLayout = new QHBoxLayout(routeCard);
    routeLayout->setContentsMargins(20, 16, 20, 16);
    auto *routeCopy = new QVBoxLayout;
    auto *routeTitle = new QLabel(tr("Routing mode"), routeCard);
    routeTitle->setObjectName("onlineSetiCardTitle");
    auto *routeDesc = new QLabel(tr("Manage direct traffic, VPN routing and DNS behavior"), routeCard);
    routeDesc->setObjectName("onlineSetiMutedText");
    routeDesc->setWordWrap(true);
    routeCopy->addWidget(routeTitle);
    routeCopy->addWidget(routeDesc);
    routeLayout->addLayout(routeCopy, 1);
    auto *routeButton = new QPushButton(tr("Open routes  ›"), routeCard);
    routeButton->setObjectName("onlineSetiSecondaryButton");
    routeLayout->addWidget(routeButton);
    routeCard->setMinimumHeight(82);
    routeCard->setMaximumHeight(104);
    cards->addWidget(routeCard, 2, 0, 1, 2);
    homeOuter->addLayout(cards);
    homeOuter->addStretch();

    auto *serversPage = new QWidget(onlineSetiPages);
    serversPage->setObjectName("onlineSetiServersPage");
    auto *serversLayout = new QVBoxLayout(serversPage);
    serversLayout->setContentsMargins(26, 24, 26, 22);
    serversLayout->setSpacing(12);
    auto *serversHeader = new QHBoxLayout;
    auto *serversCopy = new QVBoxLayout;
    auto *serversHeading = new QLabel(tr("Servers"), serversPage);
    serversHeading->setObjectName("onlineSetiPageHeading");
    auto *serversIntro = new QLabel(tr("Choose a profile or manage your subscriptions."), serversPage);
    serversIntro->setObjectName("onlineSetiMutedText");
    serversIntro->setWordWrap(true);
    serversCopy->addWidget(serversHeading);
    serversCopy->addWidget(serversIntro);
    serversHeader->addLayout(serversCopy, 1);
    auto *importProfile = new QPushButton(tr("Import profile"), serversPage);
    importProfile->setObjectName("onlineSetiSecondaryButton");
    serversHeader->addWidget(importProfile, 0, Qt::AlignVCenter);
    auto *manageGroups = new QPushButton(tr("Groups and subscriptions  ▾"), serversPage);
    manageGroups->setObjectName("onlineSetiSecondaryButton");
    serversHeader->addWidget(manageGroups, 0, Qt::AlignVCenter);
    serversLayout->addLayout(serversHeader);
    serversLayout->setAlignment(serversHeader, Qt::AlignTop);
    legacyPage->setParent(serversPage);
    auto *serverContent = new QStackedWidget(serversPage);
    serverContent->setObjectName("onlineSetiServerContent");
    serverContent->addWidget(legacyPage);
    serversLayout->addWidget(serverContent, 1);
    ui->data_view->hide();
    ui->splitter->widget(1)->hide();
    auto *emptyProfiles = new QFrame(serverContent);
    emptyProfiles->setObjectName("onlineSetiEmptyProfiles");
    auto *emptyProfilesLayout = new QVBoxLayout(emptyProfiles);
    emptyProfilesLayout->setContentsMargins(22, 18, 22, 18);
    emptyProfilesLayout->setSpacing(7);
    auto *emptyProfilesTitle = new QLabel(tr("No profiles in this group"), emptyProfiles);
    emptyProfilesTitle->setObjectName("onlineSetiCardValue");
    auto *emptyProfilesText = new QLabel(tr("Import a profile or choose another group or subscription."), emptyProfiles);
    emptyProfilesText->setObjectName("onlineSetiMutedText");
    emptyProfilesText->setWordWrap(true);
    emptyProfilesText->setAlignment(Qt::AlignCenter);
    emptyProfilesText->setFixedWidth(440);
    emptyProfilesLayout->addStretch();
    emptyProfilesLayout->addWidget(emptyProfilesTitle, 0, Qt::AlignHCenter);
    emptyProfilesLayout->addWidget(emptyProfilesText, 0, Qt::AlignHCenter);
    auto *emptyImport = new QPushButton(tr("Import profile"), emptyProfiles);
    emptyImport->setObjectName("onlineSetiPrimaryButton");
    emptyProfilesLayout->addWidget(emptyImport, 0, Qt::AlignHCenter);
    emptyProfilesLayout->addStretch();
    connect(emptyImport, &QPushButton::clicked, ui->actionAdd_profile_from_File, &QAction::trigger);
    serverContent->addWidget(emptyProfiles);
    const auto refreshEmptyProfiles = [this, emptyProfiles, serverContent, serversLayout] {
        const bool isEmpty = !profilesFilterModel || profilesFilterModel->rowCount() == 0;
        serverContent->setMaximumHeight(isEmpty ? 240 : QWIDGETSIZE_MAX);
        serversLayout->setAlignment(serverContent, isEmpty ? Qt::AlignTop : Qt::Alignment());
        serverContent->setCurrentWidget(isEmpty ? static_cast<QWidget*>(emptyProfiles)
                                                : static_cast<QWidget*>(ui->centralwidget));
    };
    refreshEmptyProfiles();
    connect(profilesFilterModel, &QAbstractItemModel::rowsInserted, this, refreshEmptyProfiles);
    connect(profilesFilterModel, &QAbstractItemModel::rowsRemoved, this, refreshEmptyProfiles);
    connect(profilesFilterModel, &QAbstractItemModel::modelReset, this, refreshEmptyProfiles);
    connect(profilesFilterModel, &QAbstractItemModel::layoutChanged, this, refreshEmptyProfiles);
    connect(ui->actionRuntime_Stats, &QAction::triggered, this, [this] {
        ui->splitter->widget(1)->show();
        ui->down_tab->show();
        QTimer::singleShot(0, this, [this] {
            const int height = ui->splitter->height();
            if (height > 0) ui->splitter->setSizes({height * 2 / 3, height / 3});
        });
    });
    for (auto *widget : {static_cast<QWidget*>(ui->toolButton_program), static_cast<QWidget*>(ui->toolButton_preferences),
                         static_cast<QWidget*>(ui->toolButton_testing), static_cast<QWidget*>(ui->toolButton_routing),
                         static_cast<QWidget*>(ui->toolButton_tools), static_cast<QWidget*>(ui->toolButton_startstop),
                         static_cast<QWidget*>(ui->checkBox_VPN), static_cast<QWidget*>(ui->checkBox_SystemProxy),
                         static_cast<QWidget*>(ui->label_running), static_cast<QWidget*>(ui->label_inbound),
                         static_cast<QWidget*>(ui->label_speed)}) {
        widget->hide();
    }
    QTimer::singleShot(0, this, [this] {
        const int height = ui->splitter->height();
        if (height > 0) ui->splitter->setSizes({height * 4 / 5, height / 5});
    });
    connect(importProfile, &QPushButton::clicked, ui->actionAdd_profile_from_File, &QAction::trigger);
    connect(manageGroups, &QPushButton::clicked, this, [manageGroups, this] {
        ui->menuTesting->popup(manageGroups->mapToGlobal(QPoint(0, manageGroups->height())));
    });
    auto *routesPage = new QWidget(onlineSetiPages);
    routesPage->setObjectName("onlineSetiSubPage");
    auto *routesLayout = new QVBoxLayout(routesPage);
    routesLayout->setContentsMargins(30, 28, 30, 28);
    routesLayout->setSpacing(15);
    auto *routesHeading = new QLabel(tr("Routing"), routesPage);
    routesHeading->setObjectName("onlineSetiPageHeading");
    auto *routesIntro = new QLabel(tr("Choose how apps and destinations use your connection."), routesPage);
    routesIntro->setObjectName("onlineSetiMutedText");
    routesLayout->addWidget(routesHeading);
    routesLayout->addWidget(routesIntro);
    auto *routesGrid = new QGridLayout;
    routesGrid->setHorizontalSpacing(16);
    routesGrid->setVerticalSpacing(16);
    auto *routesSummary = new QFrame(routesPage);
    routesSummary->setObjectName("onlineSetiInfoCard");
    auto *routesSummaryLayout = new QVBoxLayout(routesSummary);
    routesSummaryLayout->setContentsMargins(20, 18, 20, 18);
    routesSummaryLayout->setSpacing(12);
    auto *routesCardTitle = new QLabel(tr("Active routing profile"), routesSummary);
    routesCardTitle->setObjectName("onlineSetiCardTitle");
    onlineSetiRoutingProfile = new QLabel(routesSummary);
    onlineSetiRoutingProfile->setObjectName("onlineSetiCardValue");
    auto *routesMeta = new QLabel(tr("Local networks and configured exclusions can stay direct."), routesSummary);
    routesMeta->setObjectName("onlineSetiMutedText");
    routesMeta->setWordWrap(true);
    routesSummaryLayout->addWidget(routesCardTitle);
    routesSummaryLayout->addWidget(onlineSetiRoutingProfile);
    routesSummaryLayout->addWidget(routesMeta);
    auto *routingBehavior = new QFrame(routesPage);
    routingBehavior->setObjectName("onlineSetiInfoCard");
    auto *routingBehaviorLayout = new QVBoxLayout(routingBehavior);
    routingBehaviorLayout->setContentsMargins(20, 18, 20, 18);
    routingBehaviorLayout->setSpacing(11);
    auto *behaviorTitle = new QLabel(tr("Traffic behavior"), routingBehavior);
    behaviorTitle->setObjectName("onlineSetiCardValue");
    routingBehaviorLayout->addWidget(behaviorTitle);
    for (const auto &rowText : {tr("Traffic follows the active profile and its rules"),
                                tr("Application, domain and address rules are configurable"),
                                tr("DNS behavior follows the client configuration")}) {
        auto *row = new QLabel(QStringLiteral("✓   ") + rowText, routingBehavior);
        row->setObjectName("onlineSetiRouteRow");
        row->setWordWrap(true);
        routingBehaviorLayout->addWidget(row);
    }
    routesGrid->addWidget(routesSummary, 0, 0);
    routesGrid->addWidget(routingBehavior, 0, 1);
    auto *rulesCard = new QFrame(routesPage);
    rulesCard->setObjectName("onlineSetiInfoCard");
    auto *rulesLayout = new QVBoxLayout(rulesCard);
    rulesLayout->setContentsMargins(20, 18, 20, 18);
    auto *rulesTitle = new QLabel(tr("Routing rules"), rulesCard);
    rulesTitle->setObjectName("onlineSetiCardValue");
    auto *rulesText = new QLabel(tr("Application, domain, address and direct-connection rules are managed in routing profiles."), rulesCard);
    rulesText->setObjectName("onlineSetiMutedText");
    rulesText->setWordWrap(true);
    rulesLayout->addWidget(rulesTitle);
    rulesLayout->addWidget(rulesText);
    routesGrid->addWidget(rulesCard, 1, 0, 1, 2);
    routesLayout->addLayout(routesGrid);
    auto *manageRoutes = new QPushButton(tr("Manage routing rules and profiles"), routesPage);
    manageRoutes->setObjectName("onlineSetiPrimaryButton");
    routesLayout->addWidget(manageRoutes, 0, Qt::AlignLeft);
    routesLayout->addStretch();

    auto *appsPage = new QWidget(onlineSetiPages);
    appsPage->setObjectName("onlineSetiSubPage");
    onlineSetiAppsPage = appsPage;
    auto *appsLayout = new QVBoxLayout(appsPage);
    appsLayout->setContentsMargins(30, 28, 30, 28);
    appsLayout->setSpacing(14);
    auto *appsHeading = new QLabel(tr("Applications"), appsPage);
    appsHeading->setObjectName("onlineSetiPageHeading");
    auto *appsIntro = new QLabel(tr("Your apps use the currently selected traffic routing profile."), appsPage);
    appsIntro->setObjectName("onlineSetiMutedText");
    appsIntro->setWordWrap(true);
    appsLayout->addWidget(appsHeading);
    appsLayout->addWidget(appsIntro);
    auto *appsGrid = new QGridLayout;
    onlineSetiAppsGrid = appsGrid;
    appsGrid->setHorizontalSpacing(16);
    appsGrid->setVerticalSpacing(16);
    appsGrid->setColumnStretch(0, 1);
    appsGrid->setColumnStretch(1, 0);
    auto *appsCard = new QFrame(appsPage);
    onlineSetiAppsProfileCard = appsCard;
    appsCard->setObjectName("onlineSetiInfoCard");
    appsCard->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Maximum);
    auto *appsCardLayout = new QVBoxLayout(appsCard);
    appsCardLayout->setContentsMargins(20, 18, 20, 18);
    appsCardLayout->setSpacing(10);
    auto *appsCardTitle = new QLabel(tr("Application routing"), appsCard);
    appsCardTitle->setObjectName("onlineSetiCardValue");
    onlineSetiAppsProfile = new QLabel(appsCard);
    onlineSetiAppsProfile->setObjectName("onlineSetiMutedText");
    onlineSetiAppsProfile->setWordWrap(true);
    auto *appsRules = new QPushButton(tr("Choose a routing profile  ›"), appsCard);
    appsRules->setObjectName("onlineSetiPrimaryButton");
    appsCardLayout->addWidget(appsCardTitle);
    appsCardLayout->addWidget(onlineSetiAppsProfile);
    appsCardLayout->addStretch();
    appsCardLayout->addWidget(appsRules, 0, Qt::AlignLeft);
    auto *appRulesCard = new QFrame(appsPage);
    onlineSetiAppsRulesCard = appRulesCard;
    appRulesCard->setObjectName("onlineSetiInfoCard");
    appRulesCard->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Maximum);
    auto *appRulesLayout = new QVBoxLayout(appRulesCard);
    appRulesLayout->setContentsMargins(20, 18, 20, 18);
    appRulesLayout->setSpacing(10);
    auto *appRulesHeader = new QHBoxLayout;
    auto *appRulesTitle = new QLabel(tr("App-specific rules"), appRulesCard);
    appRulesTitle->setObjectName("onlineSetiCardValue");
    onlineSetiAppsRuleCount = new QLabel(appRulesCard);
    onlineSetiAppsRuleCount->setObjectName("onlineSetiMutedText");
    appRulesHeader->addWidget(appRulesTitle);
    appRulesHeader->addStretch();
    appRulesHeader->addWidget(onlineSetiAppsRuleCount);
    appRulesLayout->addLayout(appRulesHeader);
    onlineSetiAppsRuleList = new QWidget(appRulesCard);
    onlineSetiAppsRuleList->setObjectName("onlineSetiAppsRuleList");
    onlineSetiAppsRuleLayout = new QVBoxLayout(onlineSetiAppsRuleList);
    onlineSetiAppsRuleLayout->setContentsMargins(0, 0, 0, 0);
    onlineSetiAppsRuleLayout->setSpacing(7);
    appRulesLayout->addWidget(onlineSetiAppsRuleList);
    auto *manageAppRules = new QPushButton(tr("Manage routing rules and profiles"), appRulesCard);
    manageAppRules->setObjectName("onlineSetiSecondaryButton");
    appRulesLayout->addStretch();
    appRulesLayout->addWidget(manageAppRules, 0, Qt::AlignLeft);
    appsGrid->addWidget(appsCard, 0, 0);
    appsGrid->addWidget(appRulesCard, 1, 0);
    appsLayout->addLayout(appsGrid);
    appsPage->installEventFilter(this);
    connect(appsRules, &QPushButton::clicked, this, [appsRules, this] {
        ui->menuRouting_Menu->popup(appsRules->mapToGlobal(QPoint(0, appsRules->height())));
    });
    connect(manageAppRules, &QPushButton::clicked, this, [manageAppRules, this] {
        ui->menuRouting_Menu->popup(manageAppRules->mapToGlobal(QPoint(0, manageAppRules->height())));
    });
    appsLayout->addStretch();

    auto *morePage = new QWidget(onlineSetiPages);
    morePage->setObjectName("onlineSetiSubPage");
    auto *moreLayout = new QVBoxLayout(morePage);
    moreLayout->setContentsMargins(30, 28, 30, 28);
    moreLayout->setSpacing(12);
    auto *moreHeading = new QLabel(tr("Settings"), morePage);
    moreHeading->setObjectName("onlineSetiPageHeading");
    auto *moreIntro = new QLabel(tr("Settings, maintenance and diagnostics"), morePage);
    moreIntro->setObjectName("onlineSetiMutedText");
    moreLayout->addWidget(moreHeading);
    moreLayout->addWidget(moreIntro);
    auto *settingsGrid = new QGridLayout;
    settingsGrid->setHorizontalSpacing(16);
    settingsGrid->setVerticalSpacing(16);
    auto addSettingsCard = [this, morePage, settingsGrid](int row, int col, const QString &title,
                                                         const QString &description,
                                                         const QString &buttonText, QMenu *menu) {
        auto *card = new QFrame(morePage);
        card->setObjectName("onlineSetiInfoCard");
        auto *layout = new QVBoxLayout(card);
        layout->setContentsMargins(20, 18, 20, 18);
        layout->setSpacing(8);
        auto *name = new QLabel(title, card);
        name->setObjectName("onlineSetiCardValue");
        auto *detail = new QLabel(description, card);
        detail->setObjectName("onlineSetiMutedText");
        detail->setWordWrap(true);
        auto *button = new QPushButton(buttonText + QStringLiteral("  ›"), card);
        button->setObjectName("onlineSetiSecondaryButton");
        layout->addWidget(name);
        layout->addWidget(detail);
        layout->addWidget(button, 0, Qt::AlignLeft);
        connect(button, &QPushButton::clicked, this, [button, menu] {
            if (menu) menu->popup(button->mapToGlobal(QPoint(0, button->height())));
        });
        settingsGrid->addWidget(card, row, col);
    };
    addSettingsCard(0, 0, tr("Connection"), tr("VPN behavior, system mode and network switching."),
                    tr("Configure connection"), ui->menu_spmode);
    addSettingsCard(0, 1, tr("Application"), tr("Appearance, updates and local configuration."),
                    tr("Open application options"), ui->menu_preferences);
    addSettingsCard(1, 0, tr("Subscriptions and profiles"), tr("Manage profile groups and subscription sources."),
                    tr("Manage subscriptions"), ui->menuTesting);
    auto *diagnosticsCard = new QFrame(morePage);
    diagnosticsCard->setObjectName("onlineSetiInfoCard");
    auto *diagnosticsLayout = new QVBoxLayout(diagnosticsCard);
    diagnosticsLayout->setContentsMargins(20, 18, 20, 18);
    diagnosticsLayout->setSpacing(8);
    auto *diagnosticsTitle = new QLabel(tr("Tools and diagnostics"), diagnosticsCard);
    diagnosticsTitle->setObjectName("onlineSetiCardValue");
    auto *diagnosticsText = new QLabel(tr("Review runtime state, traffic counters and connection logs."), diagnosticsCard);
    diagnosticsText->setObjectName("onlineSetiMutedText");
    diagnosticsText->setWordWrap(true);
    auto *toolsButton = new QPushButton(tr("Open tools") + QStringLiteral("  ›"), diagnosticsCard);
    toolsButton->setObjectName("onlineSetiSecondaryButton");
    auto *runtimeButton = new QPushButton(tr("Open runtime statistics and logs"), diagnosticsCard);
    runtimeButton->setObjectName("onlineSetiMenuButton");
    diagnosticsLayout->addWidget(diagnosticsTitle);
    diagnosticsLayout->addWidget(diagnosticsText);
    diagnosticsLayout->addWidget(toolsButton, 0, Qt::AlignLeft);
    diagnosticsLayout->addWidget(runtimeButton);
    settingsGrid->addWidget(diagnosticsCard, 1, 1);
    moreLayout->addLayout(settingsGrid);
    connect(toolsButton, &QPushButton::clicked, this, [toolsButton, this] {
        ui->menuTools->popup(toolsButton->mapToGlobal(QPoint(0, toolsButton->height())));
    });
    connect(runtimeButton, &QPushButton::clicked, this, [this] {
        showOnlineSetiPage(1);
        ui->actionRuntime_Stats->trigger();
    });
    auto *updateButton = new QPushButton(tr("Check for updates"), morePage);
    updateButton->setObjectName("onlineSetiMenuButton");
    updateButton->setEnabled(ui->actionCheck_For_Update->isEnabled());
    updateButton->setObjectName("onlineSetiSecondaryButton");
    updateButton->setParent(morePage);
    connect(updateButton, &QPushButton::clicked, ui->actionCheck_For_Update, &QAction::trigger);
    auto *configButton = new QPushButton(tr("Open configuration folder"), morePage);
    configButton->setObjectName("onlineSetiMenuButton");
    configButton->setObjectName("onlineSetiSecondaryButton");
    configButton->setParent(morePage);
    connect(configButton, &QPushButton::clicked, ui->menu_open_config_folder, &QAction::trigger);
    auto *utilityRow = new QHBoxLayout;
    utilityRow->addWidget(updateButton);
    utilityRow->addWidget(configButton);
    utilityRow->addStretch();
    moreLayout->addLayout(utilityRow);
    moreLayout->addStretch();

    onlineSetiPages->addWidget(onlineSetiHome);
    onlineSetiPages->addWidget(serversPage);
    onlineSetiPages->addWidget(routesPage);
    onlineSetiPages->addWidget(appsPage);
    onlineSetiPages->addWidget(morePage);
    shellLayout->addWidget(rail);
    shellLayout->addWidget(contentHost, 1);

    const QStringList labels = {tr("Home"), tr("Servers"), tr("Routing"), tr("Applications"), tr("Settings")};
    const QStringList glyphs = {QStringLiteral("⌂"), QStringLiteral("◎"), QStringLiteral("⇄"), QStringLiteral("▦"), QStringLiteral("⚙")};
    for (int i = 0; i < labels.size(); ++i) {
        auto *button = new QPushButton(glyphs[i] + QStringLiteral("    ") + labels[i], rail);
        button->setObjectName("onlineSetiNavButton");
        button->setCheckable(true);
        button->setCursor(Qt::PointingHandCursor);
        railLayout->addWidget(button);
        onlineSetiNavButtons.push_back(button);
        connect(button, &QPushButton::clicked, this, [this, i] { showOnlineSetiPage(i); });
    }
    railLayout->addStretch();
    auto *support = new QLabel(tr("Subscription and support"), rail);
    support->setObjectName("onlineSetiRailNote");
    support->setWordWrap(true);
    auto *version = new QLabel(tr("Version %1").arg(QStringLiteral(NKR_VERSION)), rail);
    version->setObjectName("onlineSetiRailVersion");
    railLayout->addWidget(support);
    railLayout->addWidget(version);

    connect(onlineSetiPowerButton, &QPushButton::clicked, this, [this] {
        if (running != nullptr || m_profileConnecting) profile_stop(false, false, true);
        else profile_start();
    });
    connect(openServers, &QPushButton::clicked, this, [this] { showOnlineSetiPage(1); });
    connect(routeButton, &QPushButton::clicked, this, [this] { showOnlineSetiPage(2); });
    connect(manageRoutes, &QPushButton::clicked, this, [this, manageRoutes] {
        ui->menuRouting_Menu->popup(manageRoutes->mapToGlobal(QPoint(0, manageRoutes->height())));
    });
    refreshOnlineSetiDashboard();
    setCentralWidget(shell);
    showOnlineSetiPage(0);
}

void MainWindow::refreshOnlineSetiDashboard() {
    if (!onlineSetiHome || !onlineSetiConnectionStatus) return;
    const bool connected = running != nullptr;
    const bool connecting = m_profileConnecting;
    const bool stopping = m_profileDisconnecting;
    onlineSetiConnectionStatus->setText(connected ? tr("●  CONNECTED")
                                                  : connecting ? tr("●  CONNECTING")
                                                  : stopping ? tr("●  DISCONNECTING") : tr("○  NOT CONNECTED"));
    onlineSetiConnectionStatus->setProperty("connected", connected);
    onlineSetiConnectionStatus->style()->unpolish(onlineSetiConnectionStatus);
    onlineSetiConnectionStatus->style()->polish(onlineSetiConnectionStatus);
    onlineSetiPowerButton->setEnabled(!connecting && !stopping);
    onlineSetiPowerButton->setProperty("connected", connected);
    onlineSetiPowerButton->style()->unpolish(onlineSetiPowerButton);
    onlineSetiPowerButton->style()->polish(onlineSetiPowerButton);
    onlineSetiConnectionDetail->setText(connected ? tr("Connected")
                                                  : connecting ? tr("Starting a secure connection…")
                                                  : stopping ? tr("Disconnecting…") : tr("Click the power button to connect"));

    QString profileName = tr("No profile selected");
    QString profileMeta = tr("Open Servers to choose a connection profile");
    if (connected && running->outbound) {
        profileName = running->outbound->DisplayName();
        const auto group = Configs::dataManager->groupsRepo->GetGroup(running->gid);
        profileMeta = group ? group->name : tr("Connected profile");
    } else {
        const int id = get_profile_to_start();
        if (id >= 0) {
            const auto profile = Configs::dataManager->profilesRepo->GetProfile(id);
            if (profile && profile->outbound) {
                profileName = profile->outbound->DisplayName();
                const auto group = Configs::dataManager->groupsRepo->GetGroup(profile->gid);
                profileMeta = group ? group->name : tr("Ready to connect");
            }
        }
    }
    onlineSetiProfileName->setText(profileName);
    onlineSetiProfileMeta->setText(profileMeta);
    onlineSetiTraffic->setText(connected ? tr("Connection active") : tr("Waiting for connection"));
    onlineSetiRoute->setText(connected ? ui->label_speed->text().replace('\n', QStringLiteral("   ·   "))
                                       : tr("Traffic counters appear while connected"));
    const auto route = Configs::dataManager->routesRepo->GetRouteProfile(Configs::dataManager->settingsRepo->current_route_id);
    const QString routeName = route ? route->name : tr("Default routing");
    onlineSetiRoute->setToolTip(routeName);
    if (onlineSetiRoutingProfile) onlineSetiRoutingProfile->setText(routeName);
    if (onlineSetiAppsProfile) onlineSetiAppsProfile->setText(tr("Current routing profile: %1").arg(routeName));
    if (onlineSetiAppsRuleCount && onlineSetiAppsRuleLayout) {
        while (auto *item = onlineSetiAppsRuleLayout->takeAt(0)) {
            if (auto *widget = item->widget()) widget->deleteLater();
            delete item;
        }
        QStringList appRules;
        if (route && !route->isRaw) {
            for (const auto &rule : route->Rules) {
                if (!rule) continue;
                QStringList selectors;
                selectors.append(rule->process_name);
                selectors.append(rule->process_path);
                selectors.append(rule->process_path_regex);
                selectors.append(rule->package_name);
                selectors.removeAll(QString());
                if (!selectors.isEmpty()) appRules.append(selectors.join(QStringLiteral(", ")));
            }
        }
        onlineSetiAppsRuleCount->setText(tr("%n rule(s)", "app-specific routing rules", appRules.size()));
        if (appRules.isEmpty()) {
            auto *empty = new QLabel(tr("This routing profile has no app-specific rules."), onlineSetiAppsRuleList);
            empty->setObjectName("onlineSetiAppsEmpty");
            empty->setWordWrap(true);
            onlineSetiAppsRuleLayout->addWidget(empty);
        } else {
            for (const QString &selector : appRules) {
                auto *row = new QLabel(QStringLiteral("◉   ") + selector, onlineSetiAppsRuleList);
                row->setObjectName("onlineSetiAppRuleRow");
                row->setWordWrap(true);
                row->setToolTip(selector);
                onlineSetiAppsRuleLayout->addWidget(row);
            }
        }
    }
}
#endif

MainWindow::~MainWindow() {
    Subscription::updater()->SetUrlTester(nullptr);
    delete ui;
}

