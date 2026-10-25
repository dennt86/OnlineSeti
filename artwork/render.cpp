#include <QApplication>
#include <QImage>
#include <QDir>
double seasonalFrameTime = 0;
#include "SeasonalDecoration.hpp"
int main(int argc,char **argv) {
 QApplication app(argc,argv); QDir().mkpath("seasonal-assets");
 for (bool dark : {true,false}) for (QString kind : {QString("halloween"),QString("newyear")}) {
  int fps = kind=="newyear" ? 20 : 10; int frames=24*fps; int columns=20;
  QImage atlas(136*columns,46*((frames+columns-1)/columns),QImage::Format_ARGB32); atlas.fill(Qt::transparent);
  SeasonalDecoration widget; widget.resize(136,46); QPalette palette;
  palette.setColor(QPalette::Window,dark ? QColor("#1e1e1e") : QColor("#eceeef")); widget.setPalette(palette); widget.setDecoration(kind);
  for (int i=0;i<frames;i++) { seasonalFrameTime=double(i)/fps; QImage image(136,46,QImage::Format_ARGB32);image.fill(Qt::transparent);
   QPainter painter(&image); widget.render(&painter,QPoint(),QRegion(),QWidget::DrawChildren); painter.end();
   QPainter dest(&atlas); dest.drawImage((i%columns)*136,(i/columns)*46,image);
  }
  if(!atlas.save("seasonal-assets/"+kind+(dark ? "-dark.png" : "-light.png"))) return 1;
 }
}
