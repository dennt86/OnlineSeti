#pragma once

#include <QWidget>
#include <QPainter>
#include <QTimer>
#include <QElapsedTimer>
#include <QShowEvent>
#include <QHideEvent>
#include <QPointer>
#include <QHBoxLayout>
#include <QTabBar>
#include <QToolButton>
#include <cmath>

class SeasonalDecorationSpace final : public QWidget {
public:
    explicit SeasonalDecorationSpace(QWidget *parent) : QWidget(parent) {
        setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Ignored);
        setMaximumWidth(230);
    }
    QSize sizeHint() const override { return {230, 0}; }
    QSize minimumSizeHint() const override { return {0, 0}; }
};

// Hand-drawn pixel artwork, independent of theme stylesheets and screen DPI.
// This widget never accepts input and only animates while visible.
class SeasonalDecoration final : public QWidget {
public:
    explicit SeasonalDecoration(QWidget *parent = nullptr) : QWidget(parent) {
        setAttribute(Qt::WA_TransparentForMouseEvents);
        setFocusPolicy(Qt::NoFocus);
        setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Ignored);
        setMinimumSize(0, 0);
        setMaximumWidth(300);
        timer.setInterval(100);
        connect(&timer, &QTimer::timeout, this, [this] { update(); });
        clock.start();
    }
    QSize sizeHint() const override { return {230, 64}; }
    QSize minimumSizeHint() const override { return {0, 0}; }
    void setAnchors(QWidget *space, QWidget *divider, int filterWidth) {
        anchor = space; bottom = divider;
        rightInset = filterWidth + 6;
        space->installEventFilter(this); divider->installEventFilter(this);
        QTimer::singleShot(0, this, [this] { updateBounds(); });
    }
    void setDecoration(const QString &value) {
        enabled = value == QStringLiteral("halloween");
        setVisible(enabled);
        if (anchor) anchor->setVisible(enabled);
        QTimer::singleShot(0, this, [this] { updateBounds(); });
        if (enabled && isVisible()) timer.start(); else timer.stop();
        update();
    }
protected:
    bool eventFilter(QObject *, QEvent *event) override {
        if (event->type() == QEvent::Resize || event->type() == QEvent::Move || event->type() == QEvent::Show)
            QTimer::singleShot(0, this, [this] { updateBounds(); });
        return false;
    }
    void showEvent(QShowEvent *event) override { QWidget::showEvent(event); if (enabled) timer.start(); }
    void hideEvent(QHideEvent *event) override { timer.stop(); QWidget::hideEvent(event); }
    void paintEvent(QPaintEvent *) override {
        if (!enabled || width() < 90 || height() < 24) return;
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing, false);
        const qreal scale = qMin(qreal(width()) / 136.0, qreal(height()) / 46.0);
        p.translate(width() - 136 * scale, height() - 46 * scale);
        p.scale(scale, scale);
        p.setClipRect(0, 0, 136, 46);
        const double t = clock.elapsed() / 1000.0;
        const bool dark = palette().color(QPalette::Window).lightness() < 128;
        const QColor ink = dark ? QColor("#372536") : QColor("#51394e");
        auto rect = [&](int x, int y, int w, int h, const QColor &c) { p.fillRect(x, y, w, h, c); };
        auto polygon = [&](std::initializer_list<QPoint> points, const QColor &c) {
            QPolygon poly; for (auto point : points) poly << point;
            p.setPen(Qt::NoPen); p.setBrush(c); p.drawPolygon(poly);
        };
        p.save(); p.translate(-20, 0);
        // A quiet web occupies the far corner, leaving the filter below untouched.
        p.setPen(QPen(dark ? QColor("#625769") : QColor("#a99dad"), .65));
        for (QPoint end : {QPoint(112, 0), QPoint(119, 17), QPoint(138, 24), QPoint(155, 27)}) p.drawLine(QPoint(155, 0), end);
        for (int r : {8, 16, 25, 34}) {
            QPolygon web; web << QPoint(155-r, 0) << QPoint(155-r+3, r/3) << QPoint(155-r/2, r/2) << QPoint(155-r/5, r*2/3) << QPoint(155, r*3/4);
            p.drawPolyline(web);
        }
        // The spider pauses at both ends instead of bouncing mechanically.
        const int spiderY = 16 + qRound(7 * (1 - std::cos(t * .7)));
        p.setPen(QPen(dark ? QColor("#9a899e") : QColor("#77627f"), .7));
        p.drawLine(140, 6, 140, spiderY);
        const int leg = int(t * 2) % 2;
        const QColor spiderInk = dark ? QColor("#937a9e") : ink;
        p.setPen(QPen(spiderInk, 1));
        for (int side : {-1, 1}) for (int y : {-1, 1, 3}) {
            p.drawLine(140 + side*2, spiderY+y, 140+side*5, spiderY+y-2+leg);
            p.drawLine(140+side*5, spiderY+y-2+leg, 140+side*6, spiderY+y+1);
        }
        rect(138, spiderY-1, 5, 5, spiderInk); rect(139, spiderY-2, 3, 7, spiderInk);
        rect(139, spiderY, 1, 1, "#ded7e7"); rect(141, spiderY, 1, 1, "#ded7e7");
        p.restore();
        // A tiny ghost peeks from alternate edges, fully clipped inside this widget.
        const double cycle = std::fmod(t, 18.0);
        const bool above = cycle < 9;
        const double phase = std::fmod(cycle, 9.0);
        const double peek = phase < 5 ? std::pow(std::sin(phase * 3.141592653589793 / 5), 2) : 0;
        const int ghostY = above ? qRound(-18 + peek*23) : qRound(46 - peek*21);
        p.save(); p.translate(85, ghostY);
        polygon({{4,0},{11,0},{15,3},{16,8},{16,18},{13,16},{10,18},{7,16},{4,18},{0,16},{0,7},{1,3}}, ink);
        polygon({{4,1},{10,1},{14,4},{15,8},{15,15},{12,14},{10,16},{7,14},{4,16},{1,14},{1,7},{2,4}}, "#d9e5e7");
        rect(2,6,3,7,"#a3cbd1"); rect(5,5,3,4,"#203349"); rect(10,5,3,4,"#203349");
        rect(6,5,1,1,"#ffffff"); rect(11,5,1,1,"#ffffff"); rect(8,10,2,2,"#486c7b");
        p.restore();
        // Uneven overlapping leaf piles settle around the sides of the pumpkins.
        auto leaf = [&](int x, int y, double angle, double scale, const char *color) {
            p.save(); p.translate(x, y); p.rotate(angle); p.scale(scale, scale);
            polygon({{0,0},{3,-1},{2,-4},{5,-3},{6,-6},{8,-3},{11,-4},{10,-1},{13,0},{9,3},{6,4},{2,3}}, color);
            rect(5, -2, 1, 6, "#e6a05c"); p.restore();
        };
        leaf(3, 43, -28, .72, "#955132");
        leaf(10, 39, 22, .80, "#b76736");
        leaf(14, 45, -12, .86, "#cd8240");
        leaf(23, 42, -52, .66, "#a75c31");
        leaf(81, 43, -20, .70, "#ad6234");
        leaf(115, 40, 25, .76, "#955132");
        leaf(110, 45, -16, .88, "#cd8240");
        leaf(124, 44, -48, .62, "#b76736");
        // Three pumpkins share a restrained orange palette and have independent expressions.
        auto pumpkin = [&](int x, int y, double s, int variant) {
            p.save(); p.translate(x,y); p.scale(s,s);
            polygon({{5,0},{10,-2},{16,0},{21,-1},{26,2},{29,7},{29,17},{25,22},{5,22},{1,18},{0,8},{2,3}}, ink);
            polygon({{6,1},{11,0},{16,2},{21,1},{25,3},{27,8},{27,16},{23,20},{6,20},{3,16},{2,8},{4,4}}, "#d9772c");
            rect(8,2,4,17,"#ec983e"); rect(19,3,3,16,"#be5b26"); rect(5,5,2,10,"#f2ac51");
            polygon({{12,1},{12,-5},{15,-7},{19,-7},{19,-5},{16,-5},{15,1}}, "#66704a");
            rect(14,-5,1,4,"#92a165");
            const bool blink = std::fmod(t + variant * 1.7, 7.0) < .22;
            if (blink) { rect(6,8,6,2,ink); rect(18,8,6,2,ink); }
            else {
                polygon({{6,6},{12,9},{11,12},{6,11}}, ink);
                polygon({{23,6},{18,9},{19,12},{24,11}}, ink);
                rect(8,9,3,1,"#ffe89a"); rect(20,9,2,1,"#ffe89a");
            }
            polygon({{14,11},{12,14},{16,14}}, ink);
            const bool grin = int(t / 4 + variant) % 3 != 0;
            if (grin) {
                polygon({{5,14},{9,16},{12,15},{15,17},{18,15},{22,16},{25,14},{23,19},{7,19}}, ink);
                rect(9,16,2,1,"#ffe89a"); rect(19,16,2,1,"#ffe89a");
            } else { rect(10,16,10,3,ink); rect(13,16,2,1,"#ffe89a"); }
            p.restore();
        };
        pumpkin(20, 33, .58, 0); pumpkin(33, 21, 1.12, 1); pumpkin(60, 32, .64, 2);
        // Candle flame flickers by one pixel, without flashes or bloom.
        p.save(); p.translate(-14, 0);
        const int flicker = int(t*5) % 3;
        rect(111,34,7,11,ink); rect(112,34,5,10,"#e8d3a3"); rect(112,35,2,8,"#fff0c4");
        rect(115,35,1,4,"#c5a777"); rect(111,44,8,2,"#725348"); rect(114,31,1,3,ink);
        polygon({{114,24-flicker},{117,29},{116,32},{112,32},{111,29}}, "#dc8738");
        polygon({{114,27},{116,30},{114,32},{113,30}}, "#ffe6a0");
        p.restore();
        // Small foreground leaves touch only the lower edges, leaving faces clear.
        leaf(76, 45, -14, .54, "#b76736");
        leaf(91, 45, 22, .52, "#cd8240");
    }
private:
    void updateBounds() {
        if (!anchor || !bottom || !parentWidget()) return;
        const QPoint top = anchor->mapTo(parentWidget(), QPoint(0, 0));
        const int baseline = bottom->mapTo(parentWidget(), QPoint(0, bottom->height())).y();
        setGeometry(top.x(), top.y(), qMax(0, anchor->width() - rightInset), qMax(0, baseline - top.y()));
        raise();
    }
    QPointer<QWidget> anchor, bottom;
    int rightInset = 0;
    bool enabled = false;
    QTimer timer;
    QElapsedTimer clock;
};

inline SeasonalDecoration *installSeasonalDecoration(QWidget *central, QHBoxLayout *topBar,
                                                     QTabBar *tabs, QToolButton *filter) {
    auto *space = new SeasonalDecorationSpace(central);
    topBar->addWidget(space);
    auto *decoration = new SeasonalDecoration(central);
    decoration->setAnchors(space, tabs, filter->sizeHint().width());
    return decoration;
}
