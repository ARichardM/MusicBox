import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Controls.Material
import QtQuick.Effects
import "components" as Mb
import "downloads" as Dl

ApplicationWindow {
 id:w; width:1180;height:760;minimumWidth:900;minimumHeight:620;visible:true;title:"MusicBox";color:musicbox.darkMode?"#000":"#F3F5F8"
 Material.theme:musicbox.darkMode?Material.Dark:Material.Light;Material.accent:accent
 property int page:0;property bool expanded:false;property bool advanced:false;property string libraryFilter:"songs";property string libraryFolder:"";property string libraryPlaylist:"";property color panel:musicbox.darkMode?"#161618":"#FFFFFF";property color card:musicbox.darkMode?"#1C1C1E":"#E7EBF1";property color fg:musicbox.darkMode?"#FFFFFF":"#111318";property color muted:musicbox.darkMode?"#8E8E93":"#667085";property color accent:musicbox.darkMode?"#0A84FF":"#006FE6";property color line:musicbox.darkMode?"#303137":"#CBD2DC"
 property real splashPhase:0
 property real splashIntro:0
 property bool splashVisible:true
 property bool collectionAnimating:false
 property int pendingCollectionDirection:0
 property string pendingCollectionKind:""
 property string pendingCollectionName:""
 onWidthChanged:{if(width<1050&&musicbox.sidebarExpanded)musicbox.toggleSidebar()}
 Component.onCompleted:{if(width<1050&&musicbox.sidebarExpanded)musicbox.toggleSidebar()}
 function linkCount(text){return text.split(/\r?\n/).filter(function(x){return x.trim().length>0}).length}
 function clock(ms){var s=Math.max(0,Math.floor(ms/1000));return Math.floor(s/60)+":"+String(s%60).padStart(2,"0")}
 function collectionKind(){return libraryPlaylist!==""?"playlist":(libraryFolder!==""?"folder":"")}
 function collectionName(){return libraryPlaylist!==""?libraryPlaylist:libraryFolder}
 function collectionNames(kind){var source=kind==="playlist"?musicbox.playlists:musicbox.folders;var names=[];for(var i=0;i<source.length;i++)names.push(source[i].name);return names}
 function adjacentCollection(direction){var names=collectionNames(collectionKind());var index=names.indexOf(collectionName());var next=index+direction;return index>=0&&next>=0&&next<names.length?names[next]:""}
 function openLibraryCollection(kind,name){
  if(!name)return
  librarySearch.clear()
  if(kind==="playlist"){
   libraryFilter="playlists";libraryFolder="";libraryPlaylist=name
   musicbox.setLibraryPlaylist(name)
  }else{
   libraryFilter="folders";libraryPlaylist="";libraryFolder=name
   musicbox.setLibraryFolder(name)
  }
 }
 function closeLibraryCollection(){
  if(libraryPlaylist!==""){libraryPlaylist="";musicbox.setLibraryPlaylist("")}
  if(libraryFolder!==""){libraryFolder="";musicbox.setLibraryFolder("")}
 }
 function navigateAdjacentCollection(direction){
  if(collectionAnimating)return false
  var target=adjacentCollection(direction)
  if(!target)return false
  pendingCollectionDirection=direction
  pendingCollectionKind=collectionKind()
  pendingCollectionName=target
  collectionSlide.start()
  return true
 }
 component Heading:Label{font.pixelSize:22;font.bold:true;color:w.fg}
 component Icon:Image{property int size:18;property string customColor:"";width:size;height:size;Layout.preferredWidth:size;Layout.preferredHeight:size;Layout.minimumWidth:size;Layout.maximumWidth:size;Layout.minimumHeight:size;Layout.maximumHeight:size;sourceSize.width:size*3;sourceSize.height:size*3;fillMode:Image.PreserveAspectFit;smooth:true;mipmap:true;layer.enabled:true;layer.effect:MultiEffect{colorization:1;colorizationColor:customColor!==""?customColor:(musicbox.darkMode?"#FFFFFF":"#46505E")}}
 component BrandMark:Item{id:brandMark;property real phase:0;property bool animated:false;readonly property bool live:animated||brandHover.containsMouse;width:44;height:44;transform:Translate{y:brandMark.live?-1.2*Math.abs(Math.sin(brandMark.phase*Math.PI*2)):0}NumberAnimation on phase{from:0;to:1;duration:1650;loops:Animation.Infinite;easing.type:Easing.Linear;running:brandMark.live}
  Rectangle{anchors.centerIn:parent;width:parent.width*1.25;height:width;radius:width/2;color:"#5050E9FF";opacity:brandMark.animated?.35+.25*Math.abs(Math.sin(brandMark.phase*Math.PI*2)):0;scale:1+.06*Math.abs(Math.sin(brandMark.phase*Math.PI*2));layer.enabled:brandMark.animated;layer.effect:MultiEffect{blurEnabled:true;blur:1;blurMax:40}}
  Image{anchors.fill:parent;source:"../assets/logo_cube.png";fillMode:Image.PreserveAspectFit;smooth:true;mipmap:true}
  Canvas{id:brandWave;anchors.fill:parent;onPaint:{var c=getContext("2d");c.reset();var raw=[[18,238],[32,245],[43,238],[56,192],[67,190],[83,282],[94,295],[120,122],[130,128],[148,361],[158,364],[177,244],[187,242],[201,302],[220,312]];var s=Math.min(width/446,height/488);var ox=(width-446*s)/2;var oy=(height-488*s)/2;var pts=[];for(var i=0;i<raw.length;i++){var t=i/(raw.length-1);var motion=brandMark.live?Math.sin(brandMark.phase*Math.PI*2-raw[i][0]/446*Math.PI*4)*Math.sin(t*Math.PI):0;pts.push([ox+raw[i][0]*s,oy+(raw[i][1]+15*motion)*s])}c.beginPath();c.moveTo(pts[0][0],pts[0][1]);for(var j=1;j<pts.length-1;j++){var mx=(pts[j][0]+pts[j+1][0])/2;var my=(pts[j][1]+pts[j+1][1])/2;c.quadraticCurveTo(pts[j][0],pts[j][1],mx,my)}c.lineTo(pts[pts.length-1][0],pts[pts.length-1][1]);c.strokeStyle="#50E9FF";c.lineWidth=Math.max(1,9*s);c.lineCap="round";c.lineJoin="round";c.shadowColor="#20BFFF";c.shadowBlur=8*s;c.stroke()}Connections{target:brandMark;function onPhaseChanged(){brandWave.requestPaint()}}}
  MouseArea{id:brandHover;anchors.fill:parent;hoverEnabled:true;acceptedButtons:Qt.NoButton;onContainsMouseChanged:brandWave.requestPaint()}
 }
 component IconButton:AbstractButton{id:iconControl;property url iconSource;property int iconSize:17;property string iconColor:"";implicitWidth:34;implicitHeight:34;hoverEnabled:true;contentItem:Item{Icon{anchors.centerIn:parent;source:iconControl.iconSource;size:iconControl.iconSize;customColor:iconControl.iconColor;opacity:iconControl.enabled?(iconControl.hovered?1:.82):.3;Behavior on opacity{NumberAnimation{duration:100}}}}background:Rectangle{radius:10;color:iconControl.down?(musicbox.darkMode?"#303137":"#DCE2EA"):(iconControl.hovered?(musicbox.darkMode?"#23242A":"#EBEFF5"):"transparent");border.width:iconControl.activeFocus?1:0;border.color:w.accent;Behavior on color{ColorAnimation{duration:110}}}scale:down?.92:1;Behavior on scale{NumberAnimation{duration:90;easing.type:Easing.OutCubic}}}
 component CenterField:TextField{implicitHeight:48;Material.containerStyle:Material.Outlined;verticalAlignment:TextInput.AlignVCenter;selectByMouse:true;font.pixelSize:13;color:w.fg;placeholderTextColor:w.muted;Behavior on color{ColorAnimation{duration:140}}}
 component Shimmer:Item{id:shimmer;property real cornerRadius:10;clip:true;Rectangle{anchors.fill:parent;radius:shimmer.cornerRadius;color:musicbox.darkMode?"#24262B":"#E2E5EA"}Rectangle{id:wave;width:Math.max(70,parent.width*.55);height:parent.height;x:-width;gradient:Gradient{orientation:Gradient.Horizontal;GradientStop{position:0;color:"#00FFFFFF"}GradientStop{position:.5;color:musicbox.darkMode?"#553A3D44":"#AAF7F8FA"}GradientStop{position:1;color:"#00FFFFFF"}}NumberAnimation on x{from:-wave.width;to:shimmer.width;duration:1150;loops:Animation.Infinite;running:shimmer.visible}}}
 component SkeletonRow:Rectangle{width:ListView.view?ListView.view.width-20:500;height:74;radius:16;color:w.card;Shimmer{anchors.left:parent.left;anchors.leftMargin:10;anchors.verticalCenter:parent.verticalCenter;width:52;height:52;cornerRadius:12}Shimmer{anchors.left:parent.left;anchors.leftMargin:74;anchors.top:parent.top;anchors.topMargin:16;width:Math.max(120,parent.width*.55);height:15;cornerRadius:7}Shimmer{anchors.left:parent.left;anchors.leftMargin:74;anchors.top:parent.top;anchors.topMargin:43;width:Math.max(90,parent.width*.34);height:11;cornerRadius:6}}
 component CenterButton:Button{implicitHeight:44;topPadding:0;bottomPadding:0;topInset:0;bottomInset:0;leftInset:0;rightInset:0;hoverEnabled:true;contentItem:Text{text:parent.text;color:parent.enabled?(parent.highlighted?"white":w.fg):w.muted;font:parent.font;horizontalAlignment:Text.AlignHCenter;verticalAlignment:Text.AlignVCenter}}
 component CenterCombo:ComboBox{implicitHeight:44;topPadding:0;bottomPadding:0;contentItem:Text{leftPadding:12;rightPadding:28;text:parent.displayText;color:parent.enabled?w.fg:w.muted;font:parent.font;verticalAlignment:Text.AlignVCenter;elide:Text.ElideRight}}
 component SolidBar:ScrollBar{policy:ScrollBar.AlwaysOn;implicitWidth:12;padding:2;minimumSize:.08;contentItem:Rectangle{implicitWidth:8;radius:4;color:parent.pressed?w.accent:(parent.hovered?(musicbox.darkMode?"#74747C":"#77777F"):(musicbox.darkMode?"#4A4A50":"#AEAEB4"));Behavior on color{ColorAnimation{duration:120}}}background:Rectangle{implicitWidth:12;radius:6;color:musicbox.darkMode?"#17171A":"#E5E5EA"}}
 component Chip:Button{property string value;height:36;readonly property bool selected:w.libraryFilter===value;hoverEnabled:true;font.pixelSize:12;font.bold:selected;contentItem:Text{text:parent.text;color:parent.selected?"white":(parent.hovered?w.fg:w.muted);font:parent.font;horizontalAlignment:Text.AlignHCenter;verticalAlignment:Text.AlignVCenter}background:Rectangle{radius:18;color:parent.selected?w.accent:(parent.hovered?(musicbox.darkMode?"#292B31":"#E6EBF2"):w.card);border.width:parent.selected?0:1;border.color:musicbox.darkMode?"#32343B":"#D7DCE4";Behavior on color{ColorAnimation{duration:120}}}onClicked:{w.libraryFilter=value;if(value!=="playlists")w.libraryPlaylist="";if(value!=="songs")w.libraryFolder="";musicbox.setLibraryFilter(value)}}
 component AutoDownloadPill:Rectangle{id:pillRoot;property string section:"";property bool isEnabled:false;visible:section!=="";implicitHeight:34;implicitWidth:pillRow.implicitWidth+22;radius:17;color:isEnabled?(musicbox.darkMode?"#142840":"#E2EEFF"):(pillHover.containsMouse?(musicbox.darkMode?"#24272F":"#E8EDF5"):(musicbox.darkMode?"#1B1D22":"#F0F3F7"));border.width:1;border.color:isEnabled?(musicbox.darkMode?"#1D64B8":"#8CC0FF"):(pillHover.containsMouse?(musicbox.darkMode?"#3C404C":"#C4CCD8"):(musicbox.darkMode?"#2D3039":"#DDE2EC"));scale:pillHover.pressed?.96:1;Behavior on color{ColorAnimation{duration:140}}Behavior on border.color{ColorAnimation{duration:140}}Behavior on scale{NumberAnimation{duration:80;easing.type:Easing.OutCubic}}ToolTip.visible:pillHover.containsMouse;ToolTip.delay:350;ToolTip.text:isEnabled?qsTr("Auto-descarga activada: las nuevas canciones se descargarán al agregarse"):qsTr("Activar auto-descarga para esta sección")
  RowLayout{id:pillRow;anchors.centerIn:parent;spacing:8;Icon{source:"icons/download.svg";size:14;customColor:pillRoot.isEnabled?w.accent:(musicbox.darkMode?"#8E95A3":"#6D7685")}Label{text:qsTr("Auto-descarga");font.pixelSize:12;font.bold:pillRoot.isEnabled;color:pillRoot.isEnabled?w.fg:w.muted}Rectangle{id:pillTrack;implicitWidth:30;implicitHeight:17;radius:9;color:pillRoot.isEnabled?w.accent:(musicbox.darkMode?"#363943":"#C4CBD6");Behavior on color{ColorAnimation{duration:140}}Rectangle{width:13;height:13;radius:7;y:2;x:pillRoot.isEnabled?(pillTrack.width-width-2):2;color:"#FFFFFF";Behavior on x{NumberAnimation{duration:160;easing.type:Easing.OutCubic}}}}}
  MouseArea{id:pillHover;anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:{if(pillRoot.isEnabled){confirmAutoDlDialog.sectionToDisable=pillRoot.section;confirmAutoDlDialog.open()}else{musicbox.setAutoDownload(pillRoot.section,true)}}}
  function sync(){if(section)isEnabled=musicbox.isAutoDownload(section)}
  onSectionChanged:sync()
  Component.onCompleted:sync()
  Connections{target:musicbox;function onAutoDownloadChanged(){pillRoot.sync()}}
 }
 component Empty:ColumnLayout{property string emptyTitle;property string detailText;anchors.centerIn:parent;Label{Layout.alignment:Qt.AlignHCenter;text:parent.emptyTitle;color:w.fg;font.pixelSize:18;font.bold:true}Label{Layout.alignment:Qt.AlignHCenter;text:parent.detailText;color:w.muted}}
 component Nav:Button{id:navRoot;property int target;property url iconSource;property string label;implicitHeight:44;implicitWidth:44;Layout.preferredHeight:44;Layout.preferredWidth:musicbox.sidebarExpanded?-1:44;Layout.fillWidth:musicbox.sidebarExpanded;Layout.alignment:Qt.AlignHCenter;leftPadding:0;rightPadding:0;topInset:0;bottomInset:0;leftInset:0;rightInset:0;checkable:true;hoverEnabled:true;checked:w.page===target;font.bold:checked;ToolTip.visible:hovered&&!musicbox.sidebarExpanded;ToolTip.text:label;contentItem:Item{Icon{anchors.verticalCenter:parent.verticalCenter;x:musicbox.sidebarExpanded?12:Math.round((parent.width-width)/2);source:navRoot.iconSource;size:20;opacity:navRoot.checked?1:(navRoot.hovered?.9:.68);Behavior on x{NumberAnimation{duration:210;easing.type:Easing.OutCubic}}Behavior on opacity{NumberAnimation{duration:110}}}Text{visible:musicbox.sidebarExpanded;anchors.left:parent.left;anchors.leftMargin:44;anchors.right:parent.right;anchors.verticalCenter:parent.verticalCenter;text:navRoot.label;color:navRoot.checked?w.fg:(navRoot.hovered?w.fg:w.muted);font:navRoot.font;verticalAlignment:Text.AlignVCenter;opacity:visible?1:0;Behavior on opacity{NumberAnimation{duration:120}}Behavior on color{ColorAnimation{duration:120}}}}background:Rectangle{radius:12;color:navRoot.checked?(musicbox.darkMode?"#202C3D":"#E2EEFF"):(navRoot.hovered?(musicbox.darkMode?"#24262D":"#EEF2F7"):"transparent");Behavior on color{ColorAnimation{duration:120}}}onClicked:w.page=target}
 component RowItem:Rectangle{id:rowRoot;property var itemData:({});property bool web:false;property bool libraryRow:false;property var onlineList:[];property int onlineIndex:-1;property int queuePosition:-1;property bool active:!!itemData.path&&itemData.path===(musicbox.currentTrack.path||"");property bool isDownloading:musicbox.downloadingIds.indexOf(itemData.id)!==-1||(itemData.downloading||false);width:ListView.view?ListView.view.width-20:500;height:74;radius:16;color:active?(musicbox.darkMode?"#172B43":"#DDEEFF"):(mouse.containsMouse?(musicbox.darkMode?"#25282E":"#E7EDF5"):w.card);border.width:active?1:0;border.color:w.accent;scale:mouse.pressed?.995:1;Behavior on color{ColorAnimation{duration:120}}Behavior on scale{NumberAnimation{duration:90}}
  RowLayout{anchors.fill:parent;anchors.margins:10;anchors.rightMargin:84;spacing:12;Rectangle{Layout.preferredWidth:52;Layout.preferredHeight:52;radius:12;color:"#283140";clip:true;Image{id:rowCover;anchors.fill:parent;source:itemData.thumbnail||"";fillMode:Image.PreserveAspectCrop;asynchronous:true}Shimmer{anchors.fill:parent;cornerRadius:12;visible:rowCover.status===Image.Loading}Icon{anchors.centerIn:parent;visible:!itemData.thumbnail;source:"icons/music.svg";size:22}BusyIndicator{anchors.centerIn:parent;width:26;height:26;running:rowRoot.isDownloading;visible:rowRoot.isDownloading}Rectangle{id:rowDlBadge;visible:(itemData.downloaded||false)&&!rowRoot.isDownloading;anchors.right:parent.right;anchors.bottom:parent.bottom;anchors.margins:2;width:20;height:20;radius:10;gradient:Gradient{GradientStop{position:0;color:itemData.fromFolder?"#7B8494":"#50E9FF"}GradientStop{position:1;color:itemData.fromFolder?"#566071":w.accent}}border.width:2;border.color:rowRoot.color;scale:visible?1:0;opacity:visible?1:0;Behavior on scale{NumberAnimation{duration:420;easing.type:Easing.OutElastic;easing.amplitude:1.1;easing.period:.55}}Behavior on opacity{NumberAnimation{duration:180}}Icon{anchors.centerIn:parent;source:itemData.fromFolder?"icons/folder.svg":"icons/downloaded.svg";customColor:itemData.fromFolder?"#FFFFFF":"";size:12}}}ColumnLayout{Layout.fillWidth:true;Label{Layout.fillWidth:true;text:itemData.title||"";color:active?w.accent:w.fg;font.bold:true;elide:Text.ElideRight}Label{Layout.fillWidth:true;text:(itemData.subtitle||"")+(itemData.clicks>0?"  ·  🎧 "+itemData.clicks:"");color:w.muted;font.pixelSize:12;elide:Text.ElideRight}}}
  MouseArea{id:mouse;anchors.fill:parent;anchors.rightMargin:84;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:queuePosition>=0?musicbox.playQueueIndex(queuePosition):(onlineIndex>=0?musicbox.playOnlineList(onlineList,onlineIndex):(libraryRow?musicbox.playLibraryTrack(itemData):(web||itemData.web?musicbox.playOnline(itemData):musicbox.playTrack(itemData))))}
  Row{anchors.right:parent.right;anchors.rightMargin:8;anchors.verticalCenter:parent.verticalCenter;spacing:4;z:3
   IconButton{iconSource:(itemData.favorite||false)?"icons/heart_filled.svg":"icons/heart.svg";iconColor:(itemData.favorite||false)?"#FF375F":"";iconSize:18;opacity:(itemData.favorite||false)?1.0:(hovered?1.0:(mouse.containsMouse?.85:.55));ToolTip.visible:hovered;ToolTip.text:(itemData.favorite||false)?qsTr("Quitar de favoritas"):qsTr("Agregar a favoritas");onClicked:musicbox.toggleFavoriteFor(itemData)}
   IconButton{visible:(web||(itemData.web||false))&&!(itemData.downloaded||false)&&!rowRoot.isDownloading;iconSource:"icons/download.svg";iconSize:18;ToolTip.visible:hovered;ToolTip.text:qsTr("Descargar canción");onClicked:musicbox.downloadOnlineTrack(itemData)}
   IconButton{id:moreBtn;visible:!web&&!(itemData.web||false);iconSource:"icons/more.svg";iconSize:18;onClicked:rowMenu.popup(moreBtn,moreBtn.width-rowMenu.implicitWidth,moreBtn.height+4)}
  }
  Menu{id:rowMenu;margins:12;background:Rectangle{implicitWidth:250;radius:14;color:w.panel;border.width:1;border.color:w.line}MenuItem{text:active&&musicbox.playing?qsTr("Pausar"):qsTr("Reproducir");onTriggered:active?musicbox.togglePlayback():musicbox.playTrack(itemData)}MenuItem{text:itemData.favorite?qsTr("Quitar de favoritas"):qsTr("Agregar a favoritas");onTriggered:musicbox.toggleFavoriteFor(itemData)}MenuItem{text:qsTr("Reproducir a continuación");onTriggered:musicbox.playNextTrack(itemData)}MenuItem{text:qsTr("Agregar a la cola");onTriggered:musicbox.addToQueue(itemData)}MenuItem{text:qsTr("Agregar a playlist");onTriggered:{addToPlaylistDialog.targetTrack=itemData;addToPlaylistDialog.open()}}MenuItem{visible:w.libraryPlaylist!==""&&!!itemData.playlistKey;height:visible?implicitHeight:0;text:qsTr("Quitar de esta playlist");onTriggered:musicbox.removeTrackFromPlaylist(w.libraryPlaylist,itemData.playlistKey)}MenuItem{text:qsTr("Mostrar en carpeta");onTriggered:musicbox.showInFolder(itemData.path)}MenuItem{visible:!!itemData.path;height:visible?implicitHeight:0;text:qsTr("Eliminar del dispositivo");Material.foreground:"#FF453A";onTriggered:{deleteTrackDialog.targetTrack=itemData;deleteTrackDialog.open()}}} }
 component ForYouCard:Rectangle{id:fyRoot;property var itemData:({});readonly property bool active:!!itemData.id&&(musicbox.currentTrack.id||"")===itemData.id;readonly property bool isDownloading:musicbox.downloadingIds.indexOf(itemData.id)!==-1;width:172;height:226;radius:16;color:active?(musicbox.darkMode?"#172B43":"#DDEEFF"):(fyMouse.containsMouse?(musicbox.darkMode?"#25282E":"#E7EDF5"):w.card);border.width:active?1:0;border.color:w.accent;scale:fyMouse.pressed?.985:1;Behavior on color{ColorAnimation{duration:120}}Behavior on scale{NumberAnimation{duration:90}}
  Item{id:fyBox;x:8;y:8;width:156;height:156
   Image{id:fyCover;anchors.fill:parent;source:itemData.thumbnail||"";fillMode:Image.PreserveAspectCrop;asynchronous:true;visible:false}
   Rectangle{id:fyMask;anchors.fill:parent;radius:12;layer.enabled:true;visible:false}
   MultiEffect{anchors.fill:parent;source:fyCover;maskEnabled:true;maskSource:fyMask;visible:fyCover.status===Image.Ready}
   Shimmer{anchors.fill:parent;cornerRadius:12;visible:fyCover.status!==Image.Ready}
   Rectangle{anchors.fill:parent;radius:12;color:"#80000000";opacity:fyMouse.containsMouse||fyRoot.active?1:0;Behavior on opacity{NumberAnimation{duration:130}}Icon{anchors.centerIn:parent;source:fyRoot.active&&musicbox.playing?"icons/pause.svg":"icons/play.svg";customColor:"#FFFFFF";size:30}}
   BusyIndicator{anchors.centerIn:parent;width:34;height:34;running:fyRoot.isDownloading;visible:fyRoot.isDownloading}
   Rectangle{visible:(itemData.downloaded||false)&&!fyRoot.isDownloading;anchors.right:parent.right;anchors.bottom:parent.bottom;anchors.margins:6;width:22;height:22;radius:11;gradient:Gradient{GradientStop{position:0;color:itemData.fromFolder?"#7B8494":"#50E9FF"}GradientStop{position:1;color:itemData.fromFolder?"#566071":w.accent}}Icon{anchors.centerIn:parent;source:itemData.fromFolder?"icons/folder.svg":"icons/downloaded.svg";customColor:itemData.fromFolder?"#FFFFFF":"";size:13}}}
  Column{x:12;y:170;width:148;spacing:2;Label{width:parent.width;text:itemData.title||"";color:fyRoot.active?w.accent:w.fg;font.bold:true;font.pixelSize:12;elide:Text.ElideRight}Label{width:parent.width;text:itemData.subtitle||"";color:w.muted;font.pixelSize:11;elide:Text.ElideRight}}
  MouseArea{id:fyMouse;anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:fyRoot.active?musicbox.togglePlayback():musicbox.playOnline(itemData)}
  Row{anchors.right:parent.right;anchors.bottom:parent.bottom;anchors.rightMargin:6;anchors.bottomMargin:6;spacing:0;z:3
   IconButton{iconSource:(itemData.favorite||false)?"icons/heart_filled.svg":"icons/heart.svg";iconColor:(itemData.favorite||false)?"#FF375F":"";iconSize:16;implicitWidth:30;implicitHeight:30;opacity:(itemData.favorite||false)||hovered||fyMouse.containsMouse?1:.6;ToolTip.visible:hovered;ToolTip.text:(itemData.favorite||false)?qsTr("Quitar de favoritas"):qsTr("Agregar a favoritas");onClicked:musicbox.toggleFavoriteFor(itemData)}
   IconButton{visible:!(itemData.downloaded||false)&&!fyRoot.isDownloading;iconSource:"icons/download.svg";iconSize:16;implicitWidth:30;implicitHeight:30;opacity:hovered||fyMouse.containsMouse?1:.6;ToolTip.visible:hovered;ToolTip.text:qsTr("Descargar canción");onClicked:musicbox.downloadOnlineTrack(itemData)}}
 }
 component ArtistBubble:Item{id:abRoot;property var artist:({});width:112;height:150
  Item{id:abPhoto;x:8;y:4;width:96;height:96
   Rectangle{anchors.fill:parent;radius:width/2;color:w.card}
   Image{id:abImg;anchors.fill:parent;source:artist.photo||"";fillMode:Image.PreserveAspectCrop;asynchronous:true;visible:false}
   Rectangle{id:abMask;anchors.fill:parent;radius:width/2;layer.enabled:true;visible:false}
   MultiEffect{anchors.fill:parent;source:abImg;maskEnabled:true;maskSource:abMask;visible:abImg.status===Image.Ready}
   Label{anchors.centerIn:parent;visible:abImg.status!==Image.Ready;text:(artist.name||"?").charAt(0).toUpperCase();color:w.muted;font.pixelSize:34;font.bold:true}
   Rectangle{anchors.fill:parent;radius:width/2;color:"transparent";border.width:abMouse.containsMouse?3:2;border.color:abMouse.containsMouse?w.accent:(musicbox.darkMode?"#3A3D46":"#C9D0DB");Behavior on border.color{ColorAnimation{duration:130}}}
   scale:abMouse.pressed?.95:(abMouse.containsMouse?1.04:1);Behavior on scale{NumberAnimation{duration:110;easing.type:Easing.OutCubic}}}
  Label{x:0;y:106;width:parent.width;text:artist.name||"";color:w.fg;font.pixelSize:12;font.bold:true;horizontalAlignment:Text.AlignHCenter;wrapMode:Text.WordWrap;maximumLineCount:2;elide:Text.ElideRight}
  MouseArea{id:abMouse;anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:musicbox.openArtist(artist.browseId||"",artist.name||"")}}
 component AlbumCard:Rectangle{id:alRoot;property var album:({});width:156;height:206;radius:16;color:alMouse.containsMouse?(musicbox.darkMode?"#25282E":"#E7EDF5"):w.card;scale:alMouse.pressed?.985:1;Behavior on color{ColorAnimation{duration:120}}Behavior on scale{NumberAnimation{duration:90}}
  Item{x:8;y:8;width:140;height:140
   Image{id:alImg;anchors.fill:parent;source:album.thumbnail||"";fillMode:Image.PreserveAspectCrop;asynchronous:true;visible:false}
   Rectangle{id:alMask;anchors.fill:parent;radius:12;layer.enabled:true;visible:false}
   MultiEffect{anchors.fill:parent;source:alImg;maskEnabled:true;maskSource:alMask;visible:alImg.status===Image.Ready}
   Shimmer{anchors.fill:parent;cornerRadius:12;visible:alImg.status!==Image.Ready}}
  Column{x:12;y:154;width:132;spacing:2;Label{width:parent.width;text:album.title||"";color:w.fg;font.bold:true;font.pixelSize:12;elide:Text.ElideRight}Label{width:parent.width;text:(album.year||"")+(album.year&&album.kind==="single"?" · ":"")+(album.kind==="single"?qsTr("Sencillo"):"");color:w.muted;font.pixelSize:11;elide:Text.ElideRight}}
  MouseArea{id:alMouse;anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:musicbox.openAlbum(album.browse_id||"")}}
 RowLayout{anchors.fill:parent;spacing:0
  Rectangle{id:sidebar;z:10;Layout.preferredWidth:musicbox.sidebarExpanded?230:76;Layout.fillHeight:true;color:w.panel;Behavior on Layout.preferredWidth{NumberAnimation{duration:240;easing.type:Easing.OutCubic}}ColumnLayout{anchors.fill:parent;anchors.margins:musicbox.sidebarExpanded?18:14;spacing:8
   RowLayout{Layout.fillWidth:true;Layout.bottomMargin:22;Item{visible:!musicbox.sidebarExpanded;Layout.fillWidth:true}BrandMark{}ColumnLayout{visible:musicbox.sidebarExpanded;opacity:visible?1:0;Behavior on opacity{NumberAnimation{duration:140}}Label{text:"MusicBox";color:w.fg;font.pixelSize:20;font.bold:true}Label{text:qsTr("Escritorio");color:w.muted;font.pixelSize:11}}Item{visible:!musicbox.sidebarExpanded;Layout.fillWidth:true}}
   Nav{label:qsTr("Inicio");iconSource:"icons/search.svg";target:0}Nav{label:qsTr("Mi música");iconSource:"icons/music.svg";target:1}Nav{label:qsTr("Descargas");iconSource:"icons/download.svg";target:2}Nav{label:qsTr("Historial");iconSource:"icons/history.svg";target:3}Item{Layout.fillHeight:true}Button{id:accountButton;visible:musicbox.authenticated;implicitWidth:44;implicitHeight:44;Layout.preferredWidth:musicbox.sidebarExpanded?-1:44;Layout.preferredHeight:44;Layout.fillWidth:musicbox.sidebarExpanded;Layout.alignment:Qt.AlignHCenter;hoverEnabled:true;flat:true;ToolTip.visible:hovered&&!musicbox.sidebarExpanded;ToolTip.text:qsTr("Configuración");onClicked:settingsDialog.open();contentItem:Item{Rectangle{anchors.verticalCenter:parent.verticalCenter;x:musicbox.sidebarExpanded?10:Math.round((parent.width-width)/2);width:24;height:24;radius:12;color:w.accent;Text{anchors.centerIn:parent;text:(musicbox.user.name||musicbox.user.email||"U").charAt(0).toUpperCase();color:"white";font.bold:true;font.pixelSize:12}Behavior on x{NumberAnimation{duration:210;easing.type:Easing.OutCubic}}}Text{visible:musicbox.sidebarExpanded;anchors.left:parent.left;anchors.leftMargin:44;anchors.verticalCenter:parent.verticalCenter;text:qsTr("Configuración");color:accountButton.hovered?w.fg:w.muted;font.pixelSize:13;Behavior on color{ColorAnimation{duration:120}}}}background:Rectangle{radius:12;color:accountButton.hovered?(musicbox.darkMode?"#24262C":"#E9EEF5"):"transparent";Behavior on color{ColorAnimation{duration:110}}}}
  }IconButton{z:20;anchors.right:parent.right;anchors.rightMargin:-17;anchors.top:parent.top;anchors.topMargin:74;iconSource:musicbox.sidebarExpanded?"icons/sidebar_collapse.svg":"icons/sidebar_expand.svg";iconSize:16;ToolTip.visible:hovered;ToolTip.text:musicbox.sidebarExpanded?qsTr("Contraer panel"):qsTr("Expandir panel");background:Rectangle{radius:10;color:parent.hovered?(musicbox.darkMode?"#2A2B31":"#E4E9F0"):w.panel;border.width:1;border.color:musicbox.darkMode?"#303137":"#D8DDE5";Behavior on color{ColorAnimation{duration:110}}}onClicked:musicbox.toggleSidebar()}}
  Rectangle{Layout.fillWidth:true;Layout.fillHeight:true;color:w.color;ColumnLayout{anchors.fill:parent;spacing:0
   StackLayout{Layout.fillWidth:true;Layout.fillHeight:true;currentIndex:w.page
    Item{ColumnLayout{anchors.fill:parent;anchors.margins:w.width<980?22:34;spacing:18;Heading{text:qsTr("Inicio")}Rectangle{id:searchBar;Layout.fillWidth:true;height:62;radius:18;color:w.card;RowLayout{anchors.fill:parent;anchors.margins:7;spacing:8;Icon{source:"icons/search.svg";size:19}CenterField{id:search;Layout.fillWidth:true;placeholderText:qsTr("Canción, artista o enlace");onActiveFocusChanged:if(activeFocus&&musicbox.recentQueries.length)recentSearchPopup.open();onTextEdited:if(activeFocus&&musicbox.recentQueries.length)recentSearchPopup.open();onAccepted:{recentSearchPopup.close();musicbox.search(text)}Keys.onEscapePressed:recentSearchPopup.close();Keys.onDownPressed:{recentSearchPopup.open();recentSearchList.forceActiveFocus();recentSearchList.currentIndex=0}}IconButton{visible:search.text.length;iconSource:"icons/close.svg";iconSize:16;onClicked:{search.clear();musicbox.clearSearch();search.forceActiveFocus();recentSearchPopup.open()}}CenterButton{Layout.preferredWidth:104;text:qsTr("Buscar");highlighted:true;onClicked:{recentSearchPopup.close();musicbox.search(search.text)}background:Rectangle{radius:22;color:parent.down?"#0064D5":(parent.hovered?"#2997FF":w.accent);Behavior on color{ColorAnimation{duration:110}}}}}
     Popup{id:recentSearchPopup;y:searchBar.height+6;width:searchBar.width;padding:8;focus:false;closePolicy:Popup.CloseOnEscape|Popup.CloseOnPressOutside
      background:Rectangle{radius:18;color:w.card;border.width:1;border.color:w.line}
      contentItem:ColumnLayout{spacing:4
       RowLayout{Layout.fillWidth:true;Label{Layout.fillWidth:true;leftPadding:12;text:qsTr("Búsquedas recientes");color:w.muted;font.pixelSize:12}ToolButton{text:qsTr("Limpiar");onClicked:{musicbox.clearRecentQueries();recentSearchPopup.close()}}}
       ListView{id:recentSearchList;Layout.fillWidth:true;Layout.preferredHeight:Math.min(contentHeight,264);clip:true;model:musicbox.recentQueries.filter(function(query){return query.toLowerCase().indexOf(search.text.toLowerCase())>=0});spacing:2;currentIndex:-1
        function choose(query){search.text=query;recentSearchPopup.close();musicbox.search(query)}
        Keys.onReturnPressed:if(currentIndex>=0)choose(model[currentIndex])
        Keys.onEnterPressed:if(currentIndex>=0)choose(model[currentIndex])
        Keys.onEscapePressed:{recentSearchPopup.close();search.forceActiveFocus()}
        delegate:ItemDelegate{width:recentSearchList.width;height:44;highlighted:ListView.isCurrentItem;hoverEnabled:true;onClicked:recentSearchList.choose(modelData)
         background:Rectangle{radius:12;color:parent.hovered||parent.highlighted?w.line:"transparent"}
         contentItem:RowLayout{spacing:12;Icon{source:"icons/history.svg";size:17}Label{Layout.fillWidth:true;text:modelData;color:w.fg;elide:Text.ElideRight;font.pixelSize:13}}}
       }
      }
     }
    }ProgressBar{Layout.fillWidth:true;visible:musicbox.busy;indeterminate:true}Item{Layout.fillWidth:true;Layout.fillHeight:true
      ListView{anchors.fill:parent;visible:musicbox.searchResults.length>0&&!musicbox.busy;rightMargin:4;model:musicbox.searchResults;spacing:8;clip:true;delegate:RowItem{itemData:modelData;web:true}ScrollBar.vertical:SolidBar{}}
      ListView{anchors.fill:parent;visible:musicbox.busy;rightMargin:4;model:7;spacing:8;clip:true;interactive:false;delegate:SkeletonRow{}}
      ScrollView{id:discovery;anchors.fill:parent;visible:musicbox.searchResults.length===0&&!musicbox.busy;clip:true;contentWidth:availableWidth;ScrollBar.vertical:SolidBar{policy:ScrollBar.AsNeeded}ColumnLayout{width:discovery.availableWidth-12;spacing:24
       ColumnLayout{Layout.fillWidth:true;visible:musicbox.forYouArtists.length>0;spacing:8;Label{text:qsTr("Artistas para ti");color:w.fg;font.pixelSize:17;font.bold:true}ListView{Layout.fillWidth:true;Layout.preferredHeight:156;orientation:ListView.Horizontal;spacing:6;clip:true;model:musicbox.forYouArtists;delegate:ArtistBubble{artist:modelData}}}
       ColumnLayout{Layout.fillWidth:true;visible:musicbox.forYou.length>0||musicbox.forYouLoading||musicbox.forYouMode==="offline";spacing:12
        RowLayout{Layout.fillWidth:true;spacing:10;ColumnLayout{spacing:1;Label{text:qsTr("Para ti");color:w.fg;font.pixelSize:17;font.bold:true}Label{visible:text.length>0;text:musicbox.forYouMode==="seeds"?qsTr("Según lo que escuchas"):(musicbox.forYouMode==="queries"?qsTr("Según tus búsquedas"):(musicbox.forYouMode==="trending"?qsTr("Para empezar a descubrir"):""));color:w.muted;font.pixelSize:12}}Item{Layout.fillWidth:true}IconButton{iconSource:"icons/refresh.svg";iconSize:16;enabled:!musicbox.forYouLoading;ToolTip.visible:hovered;ToolTip.text:qsTr("Actualizar recomendaciones");onClicked:musicbox.refreshForYou()}}
        GridLayout{id:forYouGrid;Layout.fillWidth:true;visible:musicbox.forYou.length>0;columnSpacing:12;rowSpacing:12;columns:Math.max(1,Math.floor((discovery.availableWidth-12+12)/184))
         Repeater{model:musicbox.forYou;delegate:ForYouCard{itemData:modelData}}}
        GridLayout{Layout.fillWidth:true;visible:musicbox.forYou.length===0&&musicbox.forYouLoading;columnSpacing:12;rowSpacing:12;columns:forYouGrid.columns
         Repeater{model:6;delegate:Rectangle{width:172;height:226;radius:16;color:w.card;Shimmer{x:8;y:8;width:156;height:156;cornerRadius:12}Shimmer{x:12;y:174;width:120;height:13;cornerRadius:6}Shimmer{x:12;y:196;width:80;height:11;cornerRadius:5}}}}
        RowLayout{Layout.fillWidth:true;visible:musicbox.forYou.length===0&&!musicbox.forYouLoading&&musicbox.forYouMode==="offline";spacing:10;Label{Layout.fillWidth:true;text:qsTr("No se pudieron cargar las recomendaciones. Revisa tu conexión.");color:w.muted;wrapMode:Text.WordWrap}CenterButton{text:qsTr("Reintentar");onClicked:musicbox.refreshForYou();background:Rectangle{radius:22;color:parent.hovered?(musicbox.darkMode?"#292B31":"#E7EDF5"):w.card;border.width:1;border.color:w.line}}}}
       ColumnLayout{Layout.fillWidth:true;visible:musicbox.topCollections.length>0;spacing:10;Label{text:qsTr("Tus carpetas más escuchadas");color:w.fg;font.pixelSize:17;font.bold:true}ListView{Layout.fillWidth:true;Layout.preferredHeight:96;orientation:ListView.Horizontal;spacing:10;clip:true;model:musicbox.topCollections;delegate:Rectangle{width:210;height:88;radius:16;color:collectionMouse.containsMouse?(musicbox.darkMode?"#292B31":"#E7EDF5"):w.card;RowLayout{anchors.fill:parent;anchors.margins:10;Rectangle{Layout.preferredWidth:58;Layout.preferredHeight:58;radius:12;color:"#283140";clip:true;Image{id:collectionCover;anchors.fill:parent;source:modelData.thumbnail||"";fillMode:Image.PreserveAspectCrop;asynchronous:true}Shimmer{anchors.fill:parent;cornerRadius:12;visible:collectionCover.status===Image.Loading}Icon{anchors.centerIn:parent;visible:!modelData.thumbnail;source:"icons/music.svg";size:21}}ColumnLayout{Layout.fillWidth:true;Label{Layout.fillWidth:true;text:modelData.name;color:w.fg;font.bold:true;elide:Text.ElideRight}Label{Layout.fillWidth:true;text:modelData.subtitle;color:w.muted;font.pixelSize:11;elide:Text.ElideRight}}}MouseArea{id:collectionMouse;anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:{w.page=1;w.libraryFolder=modelData.name;musicbox.setLibraryFolder(modelData.name)}}}}}
       ColumnLayout{Layout.fillWidth:true;visible:musicbox.recentPlayed.length>0;spacing:10;Label{text:qsTr("Escuchadas recientemente");color:w.fg;font.pixelSize:17;font.bold:true}ListView{Layout.fillWidth:true;Layout.preferredHeight:154;orientation:ListView.Horizontal;spacing:12;clip:true;model:musicbox.recentPlayed;delegate:Item{width:126;height:150;Rectangle{anchors.top:parent.top;width:126;height:112;radius:15;color:w.card;clip:true;Image{id:recentCover;anchors.fill:parent;source:modelData.thumbnail||"";fillMode:Image.PreserveAspectCrop;asynchronous:true}Shimmer{anchors.fill:parent;cornerRadius:15;visible:recentCover.status===Image.Loading}Icon{anchors.centerIn:parent;visible:!modelData.thumbnail;source:"icons/music.svg";size:28}}Label{anchors.left:parent.left;anchors.right:parent.right;anchors.top:parent.top;anchors.topMargin:118;text:modelData.title;color:w.fg;font.bold:true;font.pixelSize:12;elide:Text.ElideRight}MouseArea{anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:musicbox.playTrack(modelData)}}}}
       ColumnLayout{Layout.fillWidth:true;visible:musicbox.topPlayed.length>0;spacing:10;Label{text:qsTr("Más escuchadas en tu biblioteca");color:w.fg;font.pixelSize:17;font.bold:true}Repeater{model:musicbox.topPlayed;delegate:RowItem{Layout.fillWidth:true;itemData:modelData}}}
       Item{Layout.fillWidth:true;Layout.preferredHeight:160;visible:musicbox.forYou.length===0&&!musicbox.forYouLoading&&musicbox.recentQueries.length===0&&musicbox.topCollections.length===0&&musicbox.recentPlayed.length===0&&musicbox.topPlayed.length===0;Empty{emptyTitle:qsTr("Busca algo para escuchar");detailText:qsTr("Tu actividad aparecerá aquí")}}
      }}}}
      Rectangle{id:artistPage;anchors.fill:parent;z:20;visible:musicbox.artistOpen;color:w.color;opacity:visible?1:0;Behavior on opacity{NumberAnimation{duration:160}}
       MouseArea{anchors.fill:parent;acceptedButtons:Qt.AllButtons;onWheel:function(wheel){wheel.accepted=true}}
       ScrollView{id:artistScroll;anchors.fill:parent;clip:true;contentWidth:availableWidth;ScrollBar.vertical:SolidBar{policy:ScrollBar.AsNeeded}
        ColumnLayout{width:artistScroll.availableWidth-(w.width<980?44:68);x:w.width<980?22:34;spacing:22
         Item{Layout.preferredHeight:12}
         RowLayout{Layout.fillWidth:true;spacing:18
          IconButton{iconSource:"icons/back.svg";iconSize:20;implicitWidth:44;implicitHeight:44;ToolTip.visible:hovered;ToolTip.text:qsTr("Volver");onClicked:musicbox.closeArtist()}
          Item{Layout.preferredWidth:128;Layout.preferredHeight:128
           Rectangle{anchors.fill:parent;radius:64;color:w.card}
           Image{id:artistImg;anchors.fill:parent;source:musicbox.artistPhoto;fillMode:Image.PreserveAspectCrop;asynchronous:true;visible:false}
           Rectangle{id:artistMask;anchors.fill:parent;radius:64;layer.enabled:true;visible:false}
           MultiEffect{anchors.fill:parent;source:artistImg;maskEnabled:true;maskSource:artistMask;visible:artistImg.status===Image.Ready}
           Label{anchors.centerIn:parent;visible:artistImg.status!==Image.Ready;text:(musicbox.artistName||"?").charAt(0).toUpperCase();color:w.muted;font.pixelSize:48;font.bold:true}
           Rectangle{anchors.fill:parent;radius:64;color:"transparent";border.width:3;border.color:w.accent}}
          ColumnLayout{Layout.fillWidth:true;spacing:4
           Label{Layout.fillWidth:true;text:musicbox.artistName;color:w.fg;font.pixelSize:32;font.bold:true;elide:Text.ElideRight}
           Label{visible:text.length>0;text:musicbox.artistSubscribers?qsTr("%1 suscriptores").arg(musicbox.artistSubscribers):"";color:w.muted;font.pixelSize:13}
           RowLayout{Layout.topMargin:8;spacing:10
            CenterButton{visible:musicbox.artistTopSongs.length>0;implicitWidth:150;text:qsTr("Reproducir");highlighted:true;onClicked:musicbox.playOnlineList(musicbox.artistAllSongs.length>0?musicbox.artistAllSongs:musicbox.artistTopSongs,0);background:Rectangle{radius:22;color:parent.down?"#0064D5":(parent.hovered?"#2997FF":w.accent);Behavior on color{ColorAnimation{duration:110}}}}}}}
         Label{visible:musicbox.artistError.length>0;Layout.fillWidth:true;text:musicbox.artistError;color:w.muted;wrapMode:Text.WordWrap}
         ColumnLayout{Layout.fillWidth:true;visible:musicbox.artistLoading;spacing:8;Repeater{model:5;delegate:Rectangle{Layout.fillWidth:true;height:74;radius:16;color:w.card;Shimmer{x:10;y:11;width:52;height:52;cornerRadius:12}Shimmer{x:74;y:16;width:200;height:15;cornerRadius:7}Shimmer{x:74;y:43;width:120;height:11;cornerRadius:6}}}}
         ColumnLayout{Layout.fillWidth:true;visible:musicbox.artistTopSongs.length>0;spacing:10
          Label{text:qsTr("Canciones más escuchadas");color:w.fg;font.pixelSize:19;font.bold:true}
          Repeater{model:musicbox.artistTopSongs;delegate:RowItem{Layout.fillWidth:true;itemData:modelData;web:true;onlineList:musicbox.artistTopSongs;onlineIndex:index}}
          CenterButton{visible:musicbox.artistCanLoadAll||musicbox.artistAllLoading;enabled:!musicbox.artistAllLoading;implicitWidth:260;text:musicbox.artistAllLoading?qsTr("Cargando canciones…"):qsTr("Ver todas sus canciones");onClicked:musicbox.loadAllArtistSongs();background:Rectangle{radius:22;color:parent.hovered?(musicbox.darkMode?"#292B31":"#E7EDF5"):w.card;border.width:1;border.color:w.line}}}
         ColumnLayout{Layout.fillWidth:true;visible:musicbox.artistAllSongs.length>0;spacing:10
          Label{text:qsTr("Todas sus canciones")+"  ·  "+musicbox.artistAllSongs.length;color:w.fg;font.pixelSize:19;font.bold:true}
          Repeater{model:musicbox.artistAllSongs;delegate:RowItem{Layout.fillWidth:true;itemData:modelData;web:true;onlineList:musicbox.artistAllSongs;onlineIndex:index}}}
         ColumnLayout{Layout.fillWidth:true;visible:musicbox.artistAlbums.length>0;spacing:10
          Label{text:qsTr("Álbumes");color:w.fg;font.pixelSize:19;font.bold:true}
          ListView{Layout.fillWidth:true;Layout.preferredHeight:214;orientation:ListView.Horizontal;spacing:12;clip:true;model:musicbox.artistAlbums;delegate:AlbumCard{album:modelData}}}
         ColumnLayout{Layout.fillWidth:true;visible:musicbox.artistSingles.length>0;spacing:10
          Label{text:qsTr("Sencillos y EPs");color:w.fg;font.pixelSize:19;font.bold:true}
          ListView{Layout.fillWidth:true;Layout.preferredHeight:214;orientation:ListView.Horizontal;spacing:12;clip:true;model:musicbox.artistSingles;delegate:AlbumCard{album:modelData}}}
         Item{Layout.preferredHeight:30}}}}
      Rectangle{id:albumPage;anchors.fill:parent;z:30;visible:musicbox.albumOpen;color:w.color;opacity:visible?1:0;Behavior on opacity{NumberAnimation{duration:160}}
       MouseArea{anchors.fill:parent;acceptedButtons:Qt.AllButtons;onWheel:function(wheel){wheel.accepted=true}}
       ScrollView{id:albumScroll;anchors.fill:parent;clip:true;contentWidth:availableWidth;ScrollBar.vertical:SolidBar{policy:ScrollBar.AsNeeded}
        ColumnLayout{width:albumScroll.availableWidth-(w.width<980?44:68);x:w.width<980?22:34;spacing:20
         Item{Layout.preferredHeight:12}
         RowLayout{Layout.fillWidth:true;spacing:18
          IconButton{iconSource:"icons/back.svg";iconSize:20;implicitWidth:44;implicitHeight:44;ToolTip.visible:hovered;ToolTip.text:qsTr("Volver");onClicked:musicbox.closeAlbum()}
          Item{Layout.preferredWidth:150;Layout.preferredHeight:150
           Image{id:albumImg;anchors.fill:parent;source:musicbox.albumThumbnail;fillMode:Image.PreserveAspectCrop;asynchronous:true;visible:false}
           Rectangle{id:albumMask;anchors.fill:parent;radius:16;layer.enabled:true;visible:false}
           MultiEffect{anchors.fill:parent;source:albumImg;maskEnabled:true;maskSource:albumMask;visible:albumImg.status===Image.Ready}
           Shimmer{anchors.fill:parent;cornerRadius:16;visible:albumImg.status!==Image.Ready}}
          ColumnLayout{Layout.fillWidth:true;spacing:4
           Label{Layout.fillWidth:true;text:musicbox.albumTitle;color:w.fg;font.pixelSize:28;font.bold:true;wrapMode:Text.WordWrap;maximumLineCount:2;elide:Text.ElideRight}
           Label{text:musicbox.albumArtist+(musicbox.albumYear?"  ·  "+musicbox.albumYear:"");color:w.muted;font.pixelSize:14}
           CenterButton{Layout.topMargin:8;visible:musicbox.albumTracks.length>0;implicitWidth:170;text:qsTr("Reproducir álbum");highlighted:true;onClicked:musicbox.playOnlineList(musicbox.albumTracks,0);background:Rectangle{radius:22;color:parent.down?"#0064D5":(parent.hovered?"#2997FF":w.accent);Behavior on color{ColorAnimation{duration:110}}}}}}
         ColumnLayout{Layout.fillWidth:true;visible:musicbox.albumLoading;spacing:8;Repeater{model:6;delegate:Rectangle{Layout.fillWidth:true;height:74;radius:16;color:w.card;Shimmer{x:10;y:11;width:52;height:52;cornerRadius:12}Shimmer{x:74;y:16;width:200;height:15;cornerRadius:7}}}}
         ColumnLayout{Layout.fillWidth:true;spacing:10;Repeater{model:musicbox.albumTracks;delegate:RowItem{Layout.fillWidth:true;itemData:modelData;web:true;onlineList:musicbox.albumTracks;onlineIndex:index}}}
         Item{Layout.preferredHeight:30}}}}
    }
    Item{id:libraryViewport;clip:true
     Item{id:libraryPage;anchors.fill:parent
     ColumnLayout{anchors.fill:parent;anchors.margins:w.width<980?22:34;spacing:14
      RowLayout{Layout.fillWidth:true;spacing:8
       IconButton{visible:w.collectionKind()!=="";iconSource:"icons/previous.svg";iconSize:16;Accessible.name:qsTr("Volver");ToolTip.visible:hovered;ToolTip.text:qsTr("Volver");onClicked:w.closeLibraryCollection()}
       ColumnLayout{Layout.maximumWidth:Math.max(200,w.width-610);spacing:1
        Label{objectName:"libraryContextLabel";visible:w.collectionKind()!=="";text:w.collectionKind()==="playlist"?qsTr("PLAYLIST"):qsTr("CARPETA");color:w.accent;font.pixelSize:10;font.bold:true;font.letterSpacing:1.4}
        Heading{objectName:"libraryHeading";Layout.fillWidth:true;text:w.collectionName()||(w.libraryFilter==="playlists"?"Playlists":(w.libraryFilter==="folders"?qsTr("Carpetas"):qsTr("Mi música")));elide:Text.ElideRight}
        Label{objectName:"libraryCollectionCount";visible:w.collectionKind()!=="";text:musicbox.library.length+(musicbox.library.length===1?qsTr(" canción"):qsTr(" canciones"));color:w.muted;font.pixelSize:11}
       }
       Item{Layout.fillWidth:true}
       IconButton{objectName:"previousCollectionButton";visible:w.collectionKind()!=="";enabled:w.adjacentCollection(-1)!=="";iconSource:"icons/prev_page.svg";iconSize:16;Accessible.name:w.collectionKind()==="playlist"?qsTr("Playlist anterior"):qsTr("Carpeta anterior");ToolTip.visible:hovered;ToolTip.text:Accessible.name;onClicked:w.navigateAdjacentCollection(-1)}
       IconButton{objectName:"nextCollectionButton";visible:w.collectionKind()!=="";enabled:w.adjacentCollection(1)!=="";iconSource:"icons/next_page.svg";iconSize:16;Accessible.name:w.collectionKind()==="playlist"?qsTr("Playlist siguiente"):qsTr("Carpeta siguiente");ToolTip.visible:hovered;ToolTip.text:Accessible.name;onClicked:w.navigateAdjacentCollection(1)}
       IconButton{visible:w.libraryPlaylist==="";iconSource:"icons/plus.svg";iconSize:17;ToolTip.visible:hovered;ToolTip.text:qsTr("Nueva playlist");onClicked:newPlaylistDialog.open()}
       IconButton{visible:Object.keys(musicbox.currentTrack).length>0;iconSource:"icons/locate.svg";iconSize:17;ToolTip.visible:hovered;ToolTip.text:qsTr("Ir a la canción actual");onClicked:{for(var i=0;i<musicbox.library.length;i++){if(musicbox.library[i].path===musicbox.currentTrack.path){libraryList.positionViewAtIndex(i,ListView.Center);break}}}}
       IconButton{iconSource:"icons/shuffle.svg";iconSize:17;ToolTip.visible:hovered;ToolTip.text:qsTr("Reproducir visible en aleatorio");onClicked:musicbox.shuffleVisible()}
       IconButton{iconSource:"icons/refresh.svg";iconSize:16;ToolTip.visible:hovered;ToolTip.text:qsTr("Actualizar biblioteca");onClicked:musicbox.refresh()}
      }
      Rectangle{Layout.fillWidth:true;height:60;radius:16;color:w.card;RowLayout{anchors.fill:parent;anchors.leftMargin:12;anchors.rightMargin:7;spacing:8;Icon{source:"icons/search.svg";size:18}CenterField{id:librarySearch;Layout.fillWidth:true;placeholderText:qsTr("Buscar en tu música");onTextChanged:musicbox.setLibraryQuery(text)}IconButton{visible:librarySearch.text.length;iconSource:"icons/close.svg";iconSize:16;onClicked:librarySearch.clear()}}}
      RowLayout{Layout.fillWidth:true;spacing:8;Chip{text:qsTr("Canciones");value:"songs"}Chip{text:"Playlists";value:"playlists"}Chip{text:qsTr("Carpetas");value:"folders"}Item{Layout.fillWidth:true}Label{text:w.libraryFilter==="playlists"&&w.libraryPlaylist===""?(musicbox.playlists.length+(musicbox.playlists.length===1?qsTr(" playlist"):qsTr(" playlists"))):(w.libraryFilter==="folders"&&w.libraryFolder===""&&w.libraryPlaylist===""?(musicbox.folders.length+(musicbox.folders.length===1?qsTr(" carpeta"):qsTr(" carpetas"))):(musicbox.library.length+(musicbox.library.length===1?qsTr(" canción"):qsTr(" canciones"))));color:w.muted;font.pixelSize:12}}
      RowLayout {
       objectName:"playlistTools"
       Layout.fillWidth:true
       visible:w.libraryPlaylist!==""
       spacing:6
       Mb.ArtistFilter {
        objectName:"playlistArtistFilter"
        Layout.maximumWidth:260
        artists:musicbox.libraryArtists
        selectedArtist:musicbox.libraryArtist
        artistPhotos:musicbox.artistPhotos
        onPhotoRequested:function(artist){musicbox.requestArtistPhoto(artist)}
        onArtistSelected:function(artist){musicbox.setLibraryArtist(artist)}
       }
       IconButton {
        objectName:"clearPlaylistArtistFilter"
        visible:musicbox.libraryArtist!==""
        iconSource:"icons/close.svg"
        iconSize:14
        iconColor:w.accent
        Accessible.name:qsTr("Quitar filtro de artista")
        ToolTip.visible:hovered
        ToolTip.text:qsTr("Quitar filtro de artista")
        onClicked:musicbox.setLibraryArtist("")
       }
       Item{Layout.fillWidth:true}
       AutoDownloadPill{objectName:"playlistAutoDownload";section:w.libraryPlaylist!==""?("playlist:"+w.libraryPlaylist):""}
      }
      ColumnLayout{Layout.fillWidth:true;Layout.fillHeight:true;visible:w.libraryFilter==="folders"&&w.libraryFolder===""&&w.libraryPlaylist==="";spacing:8;Label{Layout.fillWidth:true;Layout.topMargin:40;visible:musicbox.folders.length===0;horizontalAlignment:Text.AlignHCenter;wrapMode:Text.WordWrap;text:qsTr("No hay carpetas todavía. Agrega una carpeta de música en Configuración.");color:w.muted}Item{Layout.fillWidth:true;Layout.fillHeight:true;visible:musicbox.folders.length===0}GridView{id:foldersGrid;visible:musicbox.folders.length>0;Layout.fillWidth:true;Layout.fillHeight:true;cellWidth:206;cellHeight:96;clip:true;model:musicbox.folders;delegate:Rectangle{width:190;height:82;radius:15;color:folderMouse.containsMouse?(musicbox.darkMode?"#292B31":"#E7EDF5"):w.card;border.width:w.libraryFolder===modelData.name?1:0;border.color:w.accent;RowLayout{anchors.fill:parent;anchors.margins:10;spacing:12;Rectangle{Layout.preferredWidth:48;Layout.preferredHeight:48;radius:11;color:"#283140";clip:true;Image{id:folderCover;anchors.fill:parent;source:modelData.thumbnail||"";fillMode:Image.PreserveAspectCrop;asynchronous:true}Shimmer{anchors.fill:parent;cornerRadius:11;visible:folderCover.status===Image.Loading}Icon{anchors.centerIn:parent;visible:!modelData.thumbnail;source:"icons/music.svg";size:20}}ColumnLayout{Layout.fillWidth:true;Label{Layout.fillWidth:true;text:modelData.name;color:w.fg;font.bold:true;elide:Text.ElideRight}Label{text:modelData.subtitle+(modelData.clicks>0?"  ·  🎧 "+modelData.clicks:"");color:w.muted;font.pixelSize:11}}}MouseArea{id:folderMouse;anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:w.openLibraryCollection("folder",modelData.name)}}}}
      GridView{id:playlistsGrid;visible:w.libraryFilter==="playlists"&&w.libraryPlaylist==="";Layout.fillWidth:true;Layout.fillHeight:true;cellWidth:210;cellHeight:210;clip:true;model:musicbox.playlists;delegate:Rectangle{width:196;height:196;radius:18;color:plHover.containsMouse?(musicbox.darkMode?"#25282E":"#E7EDF5"):w.card;border.width:1;border.color:musicbox.darkMode?"#2C2F36":"#D5DCE6";ColumnLayout{anchors.fill:parent;anchors.margins:12;spacing:8;Rectangle{Layout.fillWidth:true;Layout.preferredHeight:116;radius:12;color:"#242A35";clip:true;Image{id:playlistCover;anchors.fill:parent;source:modelData.thumbnail||"";fillMode:Image.PreserveAspectCrop;asynchronous:true}Shimmer{anchors.fill:parent;cornerRadius:12;visible:playlistCover.status===Image.Loading}Icon{anchors.centerIn:parent;visible:!modelData.thumbnail;source:"icons/playlist.svg";size:34}Rectangle{visible:modelData.autoDownload||false;anchors.right:parent.right;anchors.top:parent.top;anchors.margins:6;width:22;height:22;radius:11;color:w.accent;Icon{anchors.centerIn:parent;source:"icons/download.svg";size:12}}}RowLayout{Layout.fillWidth:true;ColumnLayout{Layout.fillWidth:true;Label{Layout.fillWidth:true;text:modelData.name;color:w.fg;font.bold:true;font.pixelSize:13;elide:Text.ElideRight}Label{Layout.fillWidth:true;text:(modelData.description||modelData.subtitle)+(modelData.clicks>0?"  ·  🎧 "+modelData.clicks:"");color:w.muted;font.pixelSize:11;elide:Text.ElideRight}}IconButton{iconSource:"icons/more.svg";iconSize:16;ToolTip.visible:hovered;ToolTip.text:qsTr("Configurar playlist");onClicked:playlistSettingsDialog.openFor(modelData)}}}MouseArea{id:plHover;anchors.fill:parent;anchors.rightMargin:36;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:w.openLibraryCollection("playlist",modelData.name)}}ScrollBar.vertical:SolidBar{}Empty{visible:parent.count===0;emptyTitle:qsTr("No hay playlists todavía");detailText:qsTr("Crea una con el botón + o agrega canciones desde cualquier vista")}}
      ListView{id:libraryList;visible:(w.libraryFilter!=="playlists"&&!(w.libraryFilter==="folders"&&w.libraryFolder===""))||w.libraryPlaylist!=="";Layout.fillWidth:true;Layout.fillHeight:true;rightMargin:4;model:musicbox.library;spacing:8;clip:true;delegate:RowItem{itemData:modelData;libraryRow:true}add:Transition{NumberAnimation{properties:"opacity,scale";from:.7;to:1;duration:180;easing.type:Easing.OutCubic}}remove:Transition{NumberAnimation{property:"opacity";to:0;duration:100}}move:Transition{NumberAnimation{properties:"x,y";duration:190;easing.type:Easing.OutCubic}}displaced:Transition{NumberAnimation{properties:"x,y";duration:190;easing.type:Easing.OutCubic}}ScrollBar.vertical:SolidBar{}Empty{visible:parent.count===0;emptyTitle:musicbox.libraryArtist!==""?qsTr("Sin canciones con estos filtros"):(w.libraryPlaylist!==""?qsTr("Playlist vacía"):qsTr("No hay canciones aquí"));detailText:musicbox.libraryArtist!==""?qsTr("Cambia el artista o la búsqueda"):(w.libraryPlaylist!==""?qsTr("Agrega canciones a esta playlist desde el menú de 3 puntos"):qsTr("Prueba otra búsqueda o carpeta"))}}
     }}
     DragHandler {
      id:collectionDrag
      target:null
      enabled:w.collectionKind()!==""&&!w.collectionAnimating
      acceptedButtons:Qt.LeftButton
      xAxis.enabled:true
      yAxis.enabled:false
      grabPermissions:PointerHandler.CanTakeOverFromItems|PointerHandler.ApprovesTakeOverByAnything
      onActiveTranslationChanged:{
       if(!active)return
       var distance=activeTranslation.x
       var direction=distance<0?1:-1
       var resisted=w.adjacentCollection(direction)===""?distance*.18:distance
       libraryPage.x=resisted
       libraryPage.opacity=Math.max(.72,1-Math.abs(resisted)/Math.max(1,libraryViewport.width)*.36)
      }
      onActiveChanged:{
       if(active)return
       var distance=activeTranslation.x
       var direction=distance<0?1:-1
       if(Math.abs(distance)>libraryViewport.width*.16&&w.adjacentCollection(direction)!=="")
        w.navigateAdjacentCollection(direction)
       else
        collectionSnapBack.start()
      }
     }
     ParallelAnimation {
      id:collectionSnapBack
      NumberAnimation{target:libraryPage;property:"x";to:0;duration:210;easing.type:Easing.OutCubic}
      NumberAnimation{target:libraryPage;property:"opacity";to:1;duration:170}
     }
     SequentialAnimation {
      id:collectionSlide
      onStarted:w.collectionAnimating=true
      onStopped:{w.collectionAnimating=false;libraryPage.x=0;libraryPage.opacity=1}
      ParallelAnimation {
       NumberAnimation{target:libraryPage;property:"x";to:-w.pendingCollectionDirection*libraryViewport.width;duration:185;easing.type:Easing.InCubic}
       NumberAnimation{target:libraryPage;property:"opacity";to:.28;duration:155}
      }
      ScriptAction{script:{w.openLibraryCollection(w.pendingCollectionKind,w.pendingCollectionName);libraryPage.x=w.pendingCollectionDirection*libraryViewport.width*.3;libraryPage.opacity=.35}}
      ParallelAnimation {
       NumberAnimation{target:libraryPage;property:"x";to:0;duration:245;easing.type:Easing.OutCubic}
       NumberAnimation{target:libraryPage;property:"opacity";to:1;duration:190}
      }
     }
    }
    Dl.DownloadsPage{id:downloadsPage}
    Item{ColumnLayout{anchors.fill:parent;anchors.margins:34;spacing:18;Heading{text:qsTr("Historial")}Mb.Card{Layout.fillWidth:true;Layout.fillHeight:true;Dl.HistoryPanel{anchors.fill:parent;anchors.margins:16;Component.onCompleted:downloads.historyRefresh()}}}}
   }
   Rectangle{visible:Object.keys(musicbox.currentTrack).length>0;Layout.fillWidth:true;Layout.preferredHeight:w.expanded?205:92;color:w.panel;border.color:musicbox.darkMode?"#2C2C2E":"#D8D8DC";Behavior on Layout.preferredHeight{NumberAnimation{duration:240;easing.type:Easing.OutCubic}}ColumnLayout{anchors.fill:parent;anchors.margins:13;spacing:7
    RowLayout{Layout.fillWidth:true;Rectangle{Layout.preferredWidth:w.expanded?80:56;Layout.preferredHeight:width;radius:13;color:w.card;clip:true;Image{anchors.fill:parent;source:musicbox.currentTrack.thumbnail||"";fillMode:Image.PreserveAspectCrop}Icon{anchors.centerIn:parent;visible:!musicbox.currentTrack.thumbnail;source:"icons/music.svg";size:20}}ColumnLayout{Layout.fillWidth:true;Label{Layout.fillWidth:true;text:musicbox.currentTrack.title||"";color:w.fg;font.bold:true;font.pixelSize:w.expanded?18:14;elide:Text.ElideRight}Label{Layout.fillWidth:true;text:(musicbox.currentTrack.artist||qsTr("Artista desconocido"))+(musicbox.currentTrack.album?" · "+musicbox.currentTrack.album:"");color:w.muted;elide:Text.ElideRight}}IconButton{iconSource:musicbox.currentFavorite?"icons/heart_filled.svg":"icons/heart.svg";iconColor:musicbox.currentFavorite?"#FF375F":"";iconSize:18;onClicked:musicbox.toggleFavorite()}IconButton{iconSource:"icons/shuffle.svg";iconColor:musicbox.shuffleOn?w.accent:"";iconSize:17;opacity:musicbox.shuffleOn?1:.55;ToolTip.visible:hovered;ToolTip.text:musicbox.shuffleOn?qsTr("Aleatorio activado"):qsTr("Aleatorio desactivado");onClicked:{ToolTip.hide();musicbox.toggleShuffle()}}IconButton{iconSource:"icons/previous.svg";iconSize:16;onClicked:musicbox.skip(-1)}IconButton{implicitWidth:46;implicitHeight:46;iconSource:musicbox.playing?"icons/pause.svg":"icons/play.svg";iconSize:18;onClicked:musicbox.togglePlayback();background:Rectangle{radius:23;color:parent.down?"#0064D5":(parent.hovered?"#2997FF":w.accent);Behavior on color{ColorAnimation{duration:100}}}}IconButton{iconSource:"icons/next.svg";iconSize:16;onClicked:musicbox.skip(1)}IconButton{iconSource:musicbox.repeatMode===2?"icons/repeat_one.svg":"icons/repeat.svg";iconColor:musicbox.repeatMode===0?"":w.accent;iconSize:18;opacity:musicbox.repeatMode===0?.55:1;ToolTip.visible:hovered;ToolTip.text:musicbox.repeatMode===0?qsTr("Repetición desactivada"):(musicbox.repeatMode===1?qsTr("Repetir cola"):qsTr("Repetir canción"));onClicked:{ToolTip.hide();musicbox.cycleRepeat()}}IconButton{iconSource:"icons/queue.svg";iconSize:18;ToolTip.visible:hovered;ToolTip.text:qsTr("Cola");onClicked:queueDrawer.open()}IconButton{iconSource:w.expanded?"icons/collapse.svg":"icons/expand.svg";iconSize:16;onClicked:w.expanded=!w.expanded}}
    RowLayout{Layout.fillWidth:true;Label{text:w.clock(musicbox.playerPosition);color:w.muted;font.pixelSize:11}Slider{Layout.fillWidth:true;from:0;to:Math.max(1,musicbox.playerDuration);value:musicbox.playerPosition;onMoved:musicbox.seek(value)}Label{text:w.clock(musicbox.playerDuration);color:w.muted;font.pixelSize:11}IconButton{visible:w.expanded;implicitWidth:32;implicitHeight:32;iconSource:musicbox.muted||musicbox.volume===0?"icons/volume_muted.svg":"icons/volume.svg";iconSize:19;ToolTip.visible:hovered;ToolTip.text:musicbox.muted?qsTr("Activar sonido"):qsTr("Silenciar");onClicked:musicbox.toggleMute()}Slider{visible:w.expanded;Layout.preferredWidth:110;from:0;to:1;value:musicbox.volume;onMoved:musicbox.setVolume(value)}}Label{visible:w.expanded;Layout.fillWidth:true;text:qsTr("Reproduciendo desde Mi música · ")+(musicbox.currentTrack.path||"");color:w.muted;horizontalAlignment:Text.AlignHCenter;elide:Text.ElideMiddle}
   }}
  }}
 }

 property bool closeAfterCancel:false
 onClosing:function(close){if(musicbox.downloadRunning){close.accepted=false;closeDownloadDialog.open()}}
 Connections{target:musicbox;function onDownloadStateChanged(){if(w.closeAfterCancel&&!musicbox.downloadRunning)Qt.quit()}}
 Mb.MbDialog{id:closeDownloadDialog;width:Math.min(420,w.width-60);height:closeColumn.implicitHeight+48
  ColumnLayout{id:closeColumn;anchors.fill:parent;anchors.margins:24;spacing:14
   Label{text:qsTr("Cerrar");color:w.fg;font.pixelSize:18;font.bold:true}
   Label{Layout.fillWidth:true;text:qsTr("Hay una descarga en curso. ¿Cancelar y cerrar?");color:w.muted;wrapMode:Text.WordWrap}
   RowLayout{Layout.fillWidth:true;Item{Layout.fillWidth:true}
    Mb.PillButton{text:qsTr("No");onClicked:closeDownloadDialog.close()}
    Mb.PillButton{text:qsTr("Cancelar y cerrar");variant:"danger";onClicked:{closeDownloadDialog.close();w.closeAfterCancel=true;musicbox.cancelDownload()}}
   }
  }
 }
 Shortcut{sequence:"Ctrl+L";onActivated:{w.page=2;downloadsPage.focusLinks()}}
 Shortcut{sequence:"Ctrl+Return";onActivated:{if(w.page===2)downloadsPage.startDownload()}}
 Drawer{id:queueDrawer;objectName:"queueDrawer";edge:Qt.RightEdge;width:Math.min(420,w.width*.45);height:w.height;modal:false;interactive:true;background:Rectangle{color:w.panel;border.color:musicbox.darkMode?"#303137":"#D8DDE5"}Mb.QueuePanel{anchors.fill:parent;anchors.margins:18;onCloseRequested:queueDrawer.close()}}
 Dialog{id:confirmAutoDlDialog;property string sectionToDisable:"";anchors.centerIn:parent;width:370;modal:true;padding:0;topPadding:0;bottomPadding:0;leftPadding:0;rightPadding:0;background:Rectangle{radius:20;color:w.panel;border.width:1;border.color:w.line}contentItem:ColumnLayout{spacing:14;Item{height:8}Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("¿Desactivar auto-descarga?");color:w.fg;font.pixelSize:18;font.bold:true}Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("Si desactivas la auto-descarga, las canciones nuevas no se descargarán al agregarse.\nLas ya descargadas se mantendrán.");color:w.muted;font.pixelSize:13;wrapMode:Text.WordWrap;lineHeight:1.4}RowLayout{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;Layout.bottomMargin:18;spacing:10;Item{Layout.fillWidth:true}Button{text:qsTr("Cancelar");flat:true;onClicked:confirmAutoDlDialog.close()}CenterButton{implicitHeight:38;implicitWidth:110;text:qsTr("Desactivar");highlighted:true;onClicked:{musicbox.setAutoDownload(confirmAutoDlDialog.sectionToDisable,false);confirmAutoDlDialog.close()} background:Rectangle{radius:19;color:parent.down?"#0064D5":(parent.hovered?"#2997FF":w.accent)}}}}}
 Dialog{id:statisticsDialog;objectName:"statisticsDialog";anchors.centerIn:parent;width:Math.min(720,w.width-60);height:Math.min(740,w.height-50);modal:true;padding:24
  property bool showPlaylists:false
  property var data:musicbox.statistics
  property var rows:showPlaylists?data.playlists:data.songs
  function duration(ms){var seconds=Math.floor((ms||0)/1000);var minutes=Math.floor(seconds/60);return minutes>=60?Math.floor(minutes/60)+" h "+minutes%60+" min":(minutes>0?minutes+" min":seconds+" s")}
  background:Rectangle{radius:24;color:w.panel;border.width:1;border.color:w.line}
  contentItem:ColumnLayout{spacing:16
   RowLayout{Layout.fillWidth:true;ColumnLayout{Layout.fillWidth:true;spacing:4;Label{text:qsTr("Tu música en cifras");color:w.fg;font.pixelSize:26;font.bold:true}Label{text:qsTr("Cada reproducción cuenta");color:w.muted;font.pixelSize:13}}IconButton{iconSource:"icons/close.svg";onClicked:statisticsDialog.close()}}
   RowLayout{Layout.fillWidth:true;spacing:12
    Repeater{model:[{value:statisticsDialog.duration(statisticsDialog.data.ms),caption:qsTr("Tiempo escuchado")},{value:String(statisticsDialog.data.plays),caption:qsTr("Reproducciones")}]
     Rectangle{Layout.fillWidth:true;Layout.preferredHeight:100;radius:18;color:w.card;ColumnLayout{anchors.fill:parent;anchors.margins:18;spacing:5;Label{text:modelData.value;color:w.fg;font.pixelSize:26;font.bold:true}Label{text:modelData.caption;color:w.muted;font.pixelSize:12}}}}
   }
   Label{Layout.fillWidth:true;text:qsTr("El tiempo se mide desde esta actualización. Las reproducciones anteriores se conservan.");color:w.muted;font.pixelSize:12;wrapMode:Text.WordWrap}
   RowLayout{Layout.fillWidth:true;spacing:8
    Repeater{model:[qsTr("Canciones"),qsTr("Playlists")];CenterButton{Layout.fillWidth:true;text:modelData;onClicked:statisticsDialog.showPlaylists=index===1;background:Rectangle{radius:22;color:(index===1)===statisticsDialog.showPlaylists?w.fg:w.card}contentItem:Text{text:parent.text;horizontalAlignment:Text.AlignHCenter;verticalAlignment:Text.AlignVCenter;color:(index===1)===statisticsDialog.showPlaylists?w.panel:w.fg;font.pixelSize:14;font.bold:true}}}
   }
   Label{text:statisticsDialog.showPlaylists?qsTr("Tus playlists más escuchadas"):qsTr("Tus canciones más escuchadas");color:w.fg;font.pixelSize:18;font.bold:true}
   ListView{id:statisticsList;Layout.fillWidth:true;Layout.fillHeight:true;clip:true;spacing:10;model:statisticsDialog.rows;ScrollBar.vertical:SolidBar{}
    delegate:Rectangle{width:statisticsList.width;height:94;radius:18;color:w.card
     RowLayout{anchors.fill:parent;anchors.margins:14;spacing:14
      Label{text:String(index+1).padStart(2,"0");color:w.muted;font.pixelSize:12;font.bold:true;Layout.preferredWidth:22}
      Item{Layout.preferredWidth:60;Layout.preferredHeight:60
       Rectangle{anchors.fill:parent;radius:12;color:w.line}
       Image{id:statsCover;anchors.fill:parent;source:modelData.thumbnail||"";fillMode:Image.PreserveAspectCrop;asynchronous:true;visible:false}
       Rectangle{id:statsMask;anchors.fill:parent;radius:12;layer.enabled:true;visible:false}
       MultiEffect{anchors.fill:parent;source:statsCover;maskEnabled:true;maskSource:statsMask;visible:statsCover.status===Image.Ready}
       Icon{anchors.centerIn:parent;source:"icons/music.svg";size:24;visible:statsCover.status!==Image.Ready}}
      ColumnLayout{Layout.fillWidth:true;spacing:5
       Label{Layout.fillWidth:true;text:modelData.title||"";color:w.fg;font.pixelSize:14;font.bold:true;elide:Text.ElideRight}
       Label{Layout.fillWidth:true;text:(modelData.artist?modelData.artist+"  ·  ":"")+(modelData.plays||0)+" "+qsTr("reproducciones")+"  ·  "+statisticsDialog.duration(modelData.ms);color:w.muted;font.pixelSize:12;elide:Text.ElideRight}
       Rectangle{Layout.fillWidth:true;height:3;radius:2;color:w.line;Rectangle{height:3;radius:2;color:w.accent;width:parent.width*Math.max(.015,Math.min(1,(modelData.plays||0)/Math.max(1,statisticsDialog.rows.length?statisticsDialog.rows[0].plays:1)))}}
      }
     }
    }
    Label{anchors.centerIn:parent;visible:statisticsList.count===0;width:parent.width-30;horizontalAlignment:Text.AlignHCenter;wrapMode:Text.WordWrap;text:statisticsDialog.showPlaylists?qsTr("Escucha desde una playlist para ver aquí su actividad."):qsTr("Reproduce tu primera canción y empieza tu historia musical.");color:w.muted;font.pixelSize:14}
   }
  }
 }
 Dialog{id:settingsDialog;anchors.centerIn:parent;width:Math.min(540,w.width-60);height:Math.min(720,w.height-60);modal:true;padding:0;topPadding:0;bottomPadding:0;leftPadding:0;rightPadding:0
  enter:Transition{ParallelAnimation{NumberAnimation{property:"opacity";from:0;to:1;duration:200}NumberAnimation{property:"scale";from:.95;to:1;duration:280;easing.type:Easing.OutBack}}}
  exit:Transition{ParallelAnimation{NumberAnimation{property:"opacity";to:0;duration:140}NumberAnimation{property:"scale";to:.97;duration:140}}}
  background:Rectangle{radius:28;color:w.panel;border.width:1;border.color:w.line}
  contentItem:ColumnLayout{spacing:0
   RowLayout{Layout.fillWidth:true;Layout.margins:24;Layout.bottomMargin:12
    Heading{text:qsTr("Configuración")}
    Item{Layout.fillWidth:true}
    IconButton{iconSource:"icons/close.svg";onClicked:settingsDialog.close()}
   }
   ScrollView{id:settingsScroll;Layout.fillWidth:true;Layout.fillHeight:true;clip:true;contentWidth:availableWidth
    ColumnLayout{x:24;width:settingsScroll.availableWidth-48;spacing:12
     Rectangle{Layout.fillWidth:true;Layout.preferredHeight:92;radius:22;color:w.card
      RowLayout{anchors.fill:parent;anchors.margins:18;spacing:16
       Rectangle{Layout.preferredWidth:54;Layout.preferredHeight:54;radius:27;gradient:Gradient{GradientStop{position:0;color:"#50E9FF"}GradientStop{position:1;color:w.accent}}
        Text{anchors.centerIn:parent;text:(musicbox.user.name||musicbox.user.email||"U").charAt(0).toUpperCase();color:"white";font.bold:true;font.pixelSize:22}}
       ColumnLayout{Layout.fillWidth:true;spacing:2
        Label{Layout.fillWidth:true;text:musicbox.user.name||qsTr("Cuenta de Google");color:w.fg;font.bold:true;font.pixelSize:16;elide:Text.ElideRight}
        Label{Layout.fillWidth:true;text:musicbox.user.email||"";color:w.muted;font.pixelSize:12;elide:Text.ElideRight}
       }
      }
     }
     Label{Layout.topMargin:10;Layout.leftMargin:6;text:qsTr("APARIENCIA");color:w.muted;font.bold:true;font.pixelSize:11;font.letterSpacing:1.2}
     Rectangle{Layout.fillWidth:true;Layout.preferredHeight:themeRow.implicitHeight+languageRow.implicitHeight+68;radius:22;color:w.card
      ColumnLayout{anchors.fill:parent;anchors.margins:18;spacing:16
       RowLayout{id:themeRow;Layout.fillWidth:true;spacing:14
        Rectangle{Layout.preferredWidth:40;Layout.preferredHeight:40;radius:20;color:musicbox.darkMode?"#2B3440":"#DCEBFA"
         Icon{anchors.centerIn:parent;source:musicbox.darkMode?"icons/moon.svg":"icons/sun.svg";size:18;customColor:w.accent}}
        Label{Layout.fillWidth:true;text:qsTr("Tema oscuro");color:w.fg;font.bold:true;font.pixelSize:15}
        Switch{id:themeSwitch;checked:musicbox.darkMode;onToggled:musicbox.toggleTheme()
         Connections{target:musicbox;function onDarkModeChanged(){themeSwitch.checked=musicbox.darkMode}}}
       }
       Rectangle{Layout.fillWidth:true;Layout.preferredHeight:1;color:w.line;opacity:.6}
       ColumnLayout{id:languageRow;Layout.fillWidth:true;spacing:10
        Label{text:qsTr("Idioma");color:w.fg;font.bold:true;font.pixelSize:15}
        Rectangle{Layout.fillWidth:true;Layout.preferredHeight:42;radius:21;color:w.panel;border.width:1;border.color:w.line
         Row{anchors.fill:parent;anchors.margins:3;spacing:0
          Repeater{model:[{k:"auto",t:qsTr("Automático")},{k:"es",t:"Español"},{k:"en",t:"English"}]
           delegate:Item{width:(parent.width)/3;height:parent.height
            Rectangle{anchors.fill:parent;radius:18;color:musicbox.language===modelData.k?w.accent:(langHover.containsMouse?(musicbox.darkMode?"#24262C":"#E9EEF5"):"transparent");Behavior on color{ColorAnimation{duration:140}}}
            Label{anchors.centerIn:parent;text:modelData.t;color:musicbox.language===modelData.k?"white":w.fg;font.pixelSize:12;font.bold:musicbox.language===modelData.k}
            MouseArea{id:langHover;anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:musicbox.setLanguage(modelData.k)}
           }
          }
         }
        }
       }
      }
     }
     RowLayout{Layout.fillWidth:true;Layout.topMargin:10;Layout.leftMargin:6
      Label{Layout.fillWidth:true;text:qsTr("CARPETAS DE MÚSICA");color:w.muted;font.bold:true;font.pixelSize:11;font.letterSpacing:1.2}
      Label{text:qsTr("Restablecer");color:resetHover.containsMouse?w.accent:w.muted;font.pixelSize:11;Behavior on color{ColorAnimation{duration:120}}
       MouseArea{id:resetHover;anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:musicbox.resetLibraryFolders()}}
     }
     Repeater{model:musicbox.libraryFolders
      delegate:Rectangle{Layout.fillWidth:true;Layout.preferredHeight:60;radius:20;color:w.card
       RowLayout{anchors.fill:parent;anchors.leftMargin:16;anchors.rightMargin:8;spacing:12
        Rectangle{Layout.preferredWidth:36;Layout.preferredHeight:36;radius:18;color:musicbox.darkMode?"#2B3440":"#DCEBFA"
         Icon{anchors.centerIn:parent;source:"icons/music.svg";size:16;customColor:w.accent}}
        ColumnLayout{Layout.fillWidth:true;spacing:1
         Label{Layout.fillWidth:true;text:modelData.name;color:w.fg;font.bold:true;font.pixelSize:14;elide:Text.ElideRight}
         Label{Layout.fillWidth:true;text:modelData.exists?modelData.path:qsTr("Esta carpeta ya no existe");color:modelData.exists?w.muted:"#FF453A";font.pixelSize:11;elide:Text.ElideMiddle}
        }
        IconButton{iconSource:"icons/close.svg";iconSize:14;onClicked:musicbox.removeLibraryFolder(modelData.path)}
       }
      }
     }
     Rectangle{Layout.fillWidth:true;Layout.preferredHeight:46;radius:23;color:addFolderHover.containsMouse?(musicbox.darkMode?"#24262C":"#E9EEF5"):"transparent";border.width:1;border.color:addFolderHover.containsMouse?w.accent:w.line;Behavior on color{ColorAnimation{duration:130}}Behavior on border.color{ColorAnimation{duration:130}}
      Label{anchors.centerIn:parent;text:"+ "+qsTr("Agregar carpeta");color:w.accent;font.bold:true;font.pixelSize:13}
      MouseArea{id:addFolderHover;anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:musicbox.addLibraryFolder()}}
     Label{Layout.fillWidth:true;Layout.leftMargin:6;Layout.rightMargin:6;text:qsTr("Cada dispositivo tiene sus propias carpetas; no se sincronizan.");color:w.muted;font.pixelSize:11;wrapMode:Text.WordWrap}
     Label{Layout.topMargin:10;Layout.leftMargin:6;text:qsTr("ESTADÍSTICAS");color:w.muted;font.bold:true;font.pixelSize:11;font.letterSpacing:1.2}
     Rectangle{Layout.fillWidth:true;Layout.preferredHeight:statsColumn.implicitHeight+36;radius:22;color:w.card
      ColumnLayout{id:statsColumn;anchors.fill:parent;anchors.margins:18;spacing:4
       Label{text:qsTr("Ver mis estadísticas")+"  ›";color:w.fg;font.bold:true;font.pixelSize:18}
       Label{Layout.fillWidth:true;text:qsTr("{n} clics en {m} canciones").replace("{n}",musicbox.totalClicks).replace("{m}",musicbox.playedSongs);color:w.muted;font.pixelSize:13;wrapMode:Text.WordWrap}
      }
      MouseArea{anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:statisticsDialog.open()}
     }
     Label{Layout.topMargin:10;Layout.leftMargin:6;text:qsTr("DISPOSITIVOS CONECTADOS");color:w.muted;font.bold:true;font.pixelSize:11;font.letterSpacing:1.2}
     Repeater{model:musicbox.devices
      delegate:Rectangle{id:deviceCard;Layout.fillWidth:true;Layout.preferredHeight:modelData.current?72:112;radius:22;color:w.card;border.width:modelData.current?1:0;border.color:w.accent;opacity:0
       Component.onCompleted:opacity=1
       Behavior on opacity{NumberAnimation{duration:260;easing.type:Easing.OutCubic}}
       Behavior on Layout.preferredHeight{NumberAnimation{duration:200;easing.type:Easing.OutCubic}}
       ColumnLayout{anchors.fill:parent;anchors.margins:16;spacing:4
        RowLayout{Layout.fillWidth:true;spacing:10
         Label{Layout.fillWidth:true;text:modelData.name||qsTr("Dispositivo");color:w.fg;font.bold:true;font.pixelSize:15;elide:Text.ElideRight}
         Rectangle{Layout.preferredWidth:platformLabel.implicitWidth+20;Layout.preferredHeight:24;radius:12;color:musicbox.darkMode?"#2B2D33":"#DDE3EC"
          Label{id:platformLabel;anchors.centerIn:parent;text:modelData.platform||"";color:w.muted;font.pixelSize:11;font.bold:true}}
        }
        Label{Layout.fillWidth:true;text:(modelData.current?qsTr("Este dispositivo")+" · ":"")+modelData.lastSeenText;color:modelData.current?w.accent:w.muted;font.pixelSize:12;elide:Text.ElideRight}
        Item{Layout.fillHeight:true;visible:!modelData.current}
        Rectangle{visible:!modelData.current;Layout.alignment:Qt.AlignRight;Layout.preferredWidth:disconnectLabel.implicitWidth+28;Layout.preferredHeight:32;radius:16;color:disconnectHover.containsMouse?"#26FF453A":"transparent";border.width:1;border.color:"#66FF453A";Behavior on color{ColorAnimation{duration:130}}
         Label{id:disconnectLabel;anchors.centerIn:parent;text:qsTr("Desconectar");color:"#FF453A";font.bold:true;font.pixelSize:12}
         MouseArea{id:disconnectHover;anchors.fill:parent;hoverEnabled:true;cursorShape:Qt.PointingHandCursor;onClicked:{disconnectDialog.targetId=modelData.id;disconnectDialog.targetName=modelData.name||qsTr("Dispositivo");disconnectDialog.open()}}}
       }
      }
     }
     Label{visible:musicbox.devices.length===0;Layout.alignment:Qt.AlignHCenter;text:qsTr("Todavía no hay dispositivos registrados");color:w.muted}
     Label{Layout.topMargin:10;Layout.leftMargin:6;text:qsTr("ACERCA DE");color:w.muted;font.bold:true;font.pixelSize:11;font.letterSpacing:1.2}
     Rectangle{Layout.fillWidth:true;Layout.preferredHeight:aboutColumn.implicitHeight+36;radius:22;color:w.card
      Behavior on Layout.preferredHeight{NumberAnimation{duration:200;easing.type:Easing.OutCubic}}
      ColumnLayout{id:aboutColumn;anchors.fill:parent;anchors.margins:18;spacing:10
       RowLayout{Layout.fillWidth:true;spacing:10
        ColumnLayout{Layout.fillWidth:true;spacing:2
         Label{text:"MusicBox";color:w.fg;font.bold:true;font.pixelSize:20}
         Label{text:qsTr("Versión")+" "+musicbox.appVersion;color:w.muted;font.pixelSize:13}
        }
        Rectangle{id:aboutBadge;readonly property string kind:musicbox.updateCheck;visible:kind==="uptodate"||kind==="available";Layout.preferredWidth:aboutBadgeLabel.implicitWidth+24;Layout.preferredHeight:26;radius:13
         color:kind==="available"?w.accent:(musicbox.darkMode?"#10301F":"#DDF5E6");scale:visible?1:0.6;opacity:visible?1:0
         Behavior on scale{NumberAnimation{duration:260;easing.type:Easing.OutBack}}Behavior on opacity{NumberAnimation{duration:200}}Behavior on color{ColorAnimation{duration:180}}
         Label{id:aboutBadgeLabel;anchors.centerIn:parent;text:aboutBadge.kind==="available"?qsTr("Nueva"):qsTr("Al día");color:aboutBadge.kind==="available"?"white":"#30D158";font.bold:true;font.pixelSize:12}}
       }
       Mb.SmoothProgress{Layout.fillWidth:true;Layout.preferredHeight:4;visible:musicbox.updateCheck==="checking";indeterminate:true}
       Label{id:aboutStatus;Layout.fillWidth:true;wrapMode:Text.WordWrap;color:musicbox.updateCheck==="failed"?"#FF453A":w.muted;font.pixelSize:13
        text:{var k=musicbox.updateCheck;if(k==="checking")return qsTr("Buscando actualizaciones…");if(k==="uptodate")return qsTr("Estás al día. Tienes la última versión de MusicBox.");if(k==="available")return qsTr("Hay una versión nueva. Toca para ver las novedades.");if(k==="failed")return qsTr("No se pudo comprobar. Revisa tu conexión e inténtalo de nuevo.");return qsTr("Toca el botón para ver si hay una versión nueva.")}
        Behavior on text{SequentialAnimation{NumberAnimation{target:aboutStatus;property:"opacity";to:0;duration:90}PropertyAction{}NumberAnimation{target:aboutStatus;property:"opacity";to:1;duration:180}}}}
       CenterButton{Layout.fillWidth:true;implicitHeight:46;enabled:musicbox.updateCheck!=="checking";text:{var k=musicbox.updateCheck;return k==="checking"?qsTr("Buscando…"):(k==="available"?qsTr("Ver novedades y actualizar"):(k==="uptodate"?qsTr("Volver a buscar"):(k==="failed"?qsTr("Reintentar"):qsTr("Buscar actualizaciones"))))}
        highlighted:musicbox.updateCheck==="available";onClicked:{if(musicbox.updateCheck==="available"){settingsDialog.close()}musicbox.checkUpdatesNow()}}
      }
     }
     Item{Layout.preferredHeight:4}
    }
   }
   CenterButton{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;Layout.topMargin:12;Layout.bottomMargin:22;implicitHeight:48;text:qsTr("Cerrar sesión");highlighted:true;onClicked:{settingsDialog.close();logoutDialog.open()}background:Rectangle{radius:24;color:parent.down?"#C9342B":(parent.hovered?"#FF453A":"#E0382E");Behavior on color{ColorAnimation{duration:120}}}}
  }
 }
 Dialog{id:disconnectDialog;property string targetId:"";property string targetName:"";anchors.centerIn:parent;width:390;modal:true;padding:0;topPadding:0;bottomPadding:0;leftPadding:0;rightPadding:0;background:Rectangle{radius:22;color:w.panel;border.width:1;border.color:w.line}
  contentItem:ColumnLayout{spacing:14
   Item{height:8}
   Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("¿Desconectar este dispositivo?");color:w.fg;font.pixelSize:18;font.bold:true;wrapMode:Text.WordWrap}
   Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("Se cerrará la sesión guardada en ")+disconnectDialog.targetName+qsTr(". Tendrá que volver a iniciar sesión.");color:w.muted;font.pixelSize:13;wrapMode:Text.WordWrap;lineHeight:1.4}
   RowLayout{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;Layout.bottomMargin:18;spacing:10
    Item{Layout.fillWidth:true}
    Button{text:qsTr("Cancelar");flat:true;onClicked:disconnectDialog.close()}
    CenterButton{implicitHeight:38;implicitWidth:124;text:qsTr("Desconectar");highlighted:true;onClicked:{musicbox.disconnectDevice(disconnectDialog.targetId);disconnectDialog.close()}background:Rectangle{radius:19;color:parent.down?"#C9342B":(parent.hovered?"#FF453A":"#E0382E")}}
   }
  }
 }
 Dialog{id:logoutDialog;anchors.centerIn:parent;width:370;modal:true;padding:0;topPadding:0;bottomPadding:0;leftPadding:0;rightPadding:0;background:Rectangle{radius:20;color:w.panel;border.width:1;border.color:w.line}contentItem:ColumnLayout{spacing:14;Item{height:8}Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("Cerrar sesión");color:w.fg;font.pixelSize:18;font.bold:true}Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("¿Seguro que deseas cerrar sesión?\nTus descargas se mantendrán en este dispositivo.");color:w.muted;font.pixelSize:13;wrapMode:Text.WordWrap;lineHeight:1.4}RowLayout{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;Layout.bottomMargin:18;spacing:10;Item{Layout.fillWidth:true}Button{text:qsTr("Cancelar");flat:true;onClicked:logoutDialog.close()}CenterButton{implicitHeight:38;implicitWidth:124;text:qsTr("Cerrar sesión");highlighted:true;onClicked:{logoutDialog.close();musicbox.signOut()} background:Rectangle{radius:19;color:parent.down?"#C9342B":(parent.hovered?"#FF453A":"#E0382E")}}}}}
 Dialog{id:newPlaylistDialog;anchors.centerIn:parent;width:370;modal:true;padding:0;topPadding:0;bottomPadding:0;leftPadding:0;rightPadding:0;background:Rectangle{radius:20;color:w.panel;border.width:1;border.color:w.line}contentItem:ColumnLayout{spacing:14;Item{height:8}Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("Nueva playlist");color:w.fg;font.pixelSize:18;font.bold:true}CenterField{id:newPlaylistInput;Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;placeholderText:qsTr("Nombre de la playlist");onAccepted:{if(text.trim().length>0){musicbox.createPlaylist(text.trim());clear();newPlaylistDialog.close();}}}RowLayout{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;Layout.bottomMargin:18;spacing:10;Item{Layout.fillWidth:true}Button{text:qsTr("Cancelar");flat:true;onClicked:{newPlaylistInput.clear();newPlaylistDialog.close()}}CenterButton{implicitHeight:38;implicitWidth:100;text:qsTr("Crear");highlighted:true;onClicked:{if(newPlaylistInput.text.trim().length>0){musicbox.createPlaylist(newPlaylistInput.text.trim());newPlaylistInput.clear();newPlaylistDialog.close();}} background:Rectangle{radius:19;color:parent.down?"#0064D5":(parent.hovered?"#2997FF":w.accent)}}}}}
 Dialog{id:addToPlaylistDialog;property var targetTrack:({});anchors.centerIn:parent;width:370;modal:true;padding:0;topPadding:0;bottomPadding:0;leftPadding:0;rightPadding:0;background:Rectangle{radius:20;color:w.panel;border.width:1;border.color:w.line}contentItem:ColumnLayout{spacing:12;Item{height:8}Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("Agregar a playlist");color:w.fg;font.pixelSize:18;font.bold:true}Button{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("+ Crear nueva playlist");flat:true;onClicked:{addToPlaylistDialog.close();newPlaylistDialog.open()}}ListView{Layout.fillWidth:true;Layout.leftMargin:16;Layout.rightMargin:16;implicitHeight:Math.min(220,Math.max(48,count*46));model:musicbox.playlists;clip:true;delegate:ItemDelegate{width:ListView.view.width;text:modelData.name + " (" + modelData.count + (modelData.count===1?qsTr(" canción)"):qsTr(" canciones)"));onClicked:{var target=addToPlaylistDialog.targetTrack;var playlist=modelData.name;addToPlaylistDialog.close();musicbox.addTrackToPlaylist(playlist,target)}}ScrollBar.vertical:SolidBar{}}RowLayout{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;Layout.bottomMargin:16;Item{Layout.fillWidth:true}Button{text:qsTr("Cerrar");flat:true;onClicked:addToPlaylistDialog.close()}}}}
 Dialog{id:deleteTrackDialog;property var targetTrack:({});anchors.centerIn:parent;width:400;modal:true;padding:0;topPadding:0;bottomPadding:0;leftPadding:0;rightPadding:0;background:Rectangle{radius:20;color:w.panel;border.width:1;border.color:w.line}contentItem:ColumnLayout{spacing:14;Item{height:8}Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("Eliminar canción");color:w.fg;font.pixelSize:18;font.bold:true}Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:(deleteTrackDialog.targetTrack.title||"")+"\n\n"+qsTr("Se moverá el archivo a la Papelera de reciclaje y se quitará de tus playlists.");color:w.muted;font.pixelSize:13;wrapMode:Text.WordWrap;lineHeight:1.4}RowLayout{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;Layout.bottomMargin:18;spacing:10;Item{Layout.fillWidth:true}Button{text:qsTr("Cancelar");flat:true;onClicked:deleteTrackDialog.close()}CenterButton{implicitHeight:38;implicitWidth:110;text:qsTr("Eliminar");highlighted:true;onClicked:{musicbox.deleteTrack(deleteTrackDialog.targetTrack);deleteTrackDialog.close()} background:Rectangle{radius:19;color:parent.down?"#C9342B":(parent.hovered?"#FF453A":"#E0382E")}}}}}
 Dialog{id:playlistSettingsDialog;property string targetPlaylist:"";property string targetDescription:"";property bool targetAuto:false;property bool targetIsFavorites:false;function openFor(data){targetPlaylist=data.name;targetDescription=data.description||"";targetAuto=data.autoDownload||false;targetIsFavorites=data.isFavorites||false;playlistNameField.text=targetPlaylist;playlistDescriptionField.text=targetDescription;playlistAutoSwitch.checked=targetAuto;open()}anchors.centerIn:parent;width:430;modal:true;padding:0;topPadding:0;bottomPadding:0;leftPadding:0;rightPadding:0;background:Rectangle{radius:20;color:w.panel;border.width:1;border.color:w.line}contentItem:ColumnLayout{spacing:14;Item{height:8}Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("Configurar playlist");color:w.fg;font.pixelSize:18;font.bold:true}CenterField{id:playlistNameField;Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;enabled:!playlistSettingsDialog.targetIsFavorites;placeholderText:qsTr("Nombre")}CenterField{id:playlistDescriptionField;Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;placeholderText:"Descripción (opcional)"}RowLayout{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;Label{Layout.fillWidth:true;text:qsTr("Descargar canciones nuevas");color:w.fg;font.pixelSize:13}Switch{id:playlistAutoSwitch}}RowLayout{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;Layout.bottomMargin:18;spacing:10;Button{visible:!playlistSettingsDialog.targetIsFavorites;text:qsTr("Eliminar");flat:true;Material.foreground:"#FF453A";onClicked:{playlistSettingsDialog.close();deletePlaylistDialog.targetPlaylist=playlistSettingsDialog.targetPlaylist;deletePlaylistDialog.open()}}Item{Layout.fillWidth:true}Button{text:qsTr("Cancelar");flat:true;onClicked:playlistSettingsDialog.close()}CenterButton{implicitHeight:38;implicitWidth:100;text:qsTr("Guardar");highlighted:true;onClicked:{var updated=playlistNameField.text.trim();if(!updated.length)return;musicbox.setAutoDownload("playlist:"+playlistSettingsDialog.targetPlaylist,playlistAutoSwitch.checked);musicbox.updatePlaylistDetails(playlistSettingsDialog.targetPlaylist,updated,playlistDescriptionField.text.trim());playlistSettingsDialog.close()}}}}}
 Dialog{id:deletePlaylistDialog;property string targetPlaylist:"";anchors.centerIn:parent;width:370;modal:true;padding:0;topPadding:0;bottomPadding:0;leftPadding:0;rightPadding:0;background:Rectangle{radius:20;color:w.panel;border.width:1;border.color:w.line}contentItem:ColumnLayout{spacing:14;Item{height:8}Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("Eliminar playlist");color:w.fg;font.pixelSize:18;font.bold:true}Text{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;text:qsTr("¿Seguro que deseas eliminar la playlist \"") + deletePlaylistDialog.targetPlaylist + qsTr("\"?\nLas canciones descargadas se mantendrán.");color:w.muted;font.pixelSize:13;wrapMode:Text.WordWrap;lineHeight:1.4}RowLayout{Layout.fillWidth:true;Layout.leftMargin:24;Layout.rightMargin:24;Layout.bottomMargin:18;spacing:10;Item{Layout.fillWidth:true}Button{text:qsTr("Cancelar");flat:true;onClicked:deletePlaylistDialog.close()}CenterButton{implicitHeight:38;implicitWidth:110;text:qsTr("Eliminar");highlighted:true;onClicked:{musicbox.deletePlaylist(deletePlaylistDialog.targetPlaylist);deletePlaylistDialog.close()} background:Rectangle{radius:19;color:parent.down?"#C9342B":(parent.hovered?"#FF453A":"#E0382E")}}}}}
 Rectangle{id:toast;property var info:musicbox.notice;property bool shown:!!info.text;z:960;anchors.horizontalCenter:parent.horizontalCenter;anchors.bottom:parent.bottom;anchors.bottomMargin:shown?118:96;width:Math.min(520,parent.width-48);height:toastRow.implicitHeight+28;radius:18;opacity:shown?1:0;visible:opacity>0;
  gradient:Gradient{GradientStop{position:0;color:musicbox.darkMode?"#1B2230":"#FFFFFF"}GradientStop{position:1;color:musicbox.darkMode?"#10141C":"#EEF3FA"}}border.width:1;border.color:"#6650E9FF"
  Behavior on opacity{NumberAnimation{duration:220;easing.type:Easing.OutCubic}}Behavior on anchors.bottomMargin{NumberAnimation{duration:260;easing.type:Easing.OutCubic}}
  onInfoChanged:{if(shown){toastTimer.restart()}}
  Timer{id:toastTimer;interval:9000;onTriggered:{if(!toastHover.containsMouse)musicbox.dismissNotice()}}
  MouseArea{id:toastHover;anchors.fill:parent;hoverEnabled:true;acceptedButtons:Qt.NoButton;onContainsMouseChanged:{if(!containsMouse&&toast.shown)toastTimer.restart()}}
  RowLayout{id:toastRow;anchors.fill:parent;anchors.margins:14;anchors.leftMargin:16;spacing:12
   Rectangle{Layout.alignment:Qt.AlignTop;Layout.preferredWidth:8;Layout.preferredHeight:8;Layout.topMargin:6;radius:4;color:"#50E9FF"}
   Label{Layout.fillWidth:true;text:toast.info.text||"";color:w.fg;font.pixelSize:13;wrapMode:Text.WordWrap;maximumLineCount:4;elide:Text.ElideRight;lineHeight:1.2}
   Button{visible:!!toast.info.retry;flat:true;text:qsTr("REINTENTAR");font.pixelSize:11;font.bold:true;font.letterSpacing:.8;Material.foreground:"#50E9FF";onClicked:musicbox.retryNotice()}
   IconButton{iconSource:"icons/close.svg";iconSize:14;implicitWidth:28;implicitHeight:28;onClicked:musicbox.dismissNotice()}
  }
 }
 Popup{id:updateSheet;parent:Overlay.overlay;anchors.centerIn:parent;width:Math.min(460,w.width-48);modal:true;padding:0;closePolicy:musicbox.updateState==="downloading"||musicbox.updateState==="installing"?Popup.NoAutoClose:Popup.CloseOnEscape
  property bool wanted:musicbox.updateState!=="idle"&&!w.splashVisible&&musicbox.authenticated
  property bool detailed:false
  function headline(text){var i=text.indexOf(". ");var t=text;if(i>0&&i<32){var rest=text.substring(i+2).trim();var n=rest.indexOf(". ");if(n>0)rest=rest.substring(0,n+1);t=text.substring(0,i)+" — "+rest}else if(i>0){t=text.substring(0,i+1)}if(t.length>120){var k=Math.max(t.lastIndexOf(", ",126),t.lastIndexOf("; ",126));if(k>40){t=t.substring(0,k).replace(/[,;:\s]+$/,"")+"."}else{var c=t.lastIndexOf(" ",118);t=t.substring(0,c>40?c:118).replace(/[,;:\s]+$/,"")+"…"}}return t}
  property var allNotes:musicbox.update.notes||[]
  property var briefNotes:{var r=[];for(var i=0;i<allNotes.length&&i<4;i++)r.push(headline(allNotes[i]));return r}
  property bool hasMore:{if(allNotes.length>briefNotes.length)return true;for(var i=0;i<briefNotes.length;i++)if(briefNotes[i]!==allNotes[i])return true;return false}
  onOpened:detailed=false
  onWantedChanged:{if(wanted)open();else close()}
  Component.onCompleted:{if(wanted)open()}
  onClosed:{if(musicbox.updateState==="available"||musicbox.updateState==="error")musicbox.dismissUpdate()}
  enter:Transition{ParallelAnimation{NumberAnimation{property:"opacity";from:0;to:1;duration:220}NumberAnimation{property:"scale";from:.94;to:1;duration:300;easing.type:Easing.OutBack}}}
  exit:Transition{ParallelAnimation{NumberAnimation{property:"opacity";to:0;duration:160}NumberAnimation{property:"scale";to:.97;duration:160}}}
  background:Rectangle{radius:24;color:w.panel;border.width:1;border.color:"#5550E9FF"}
  contentItem:ColumnLayout{spacing:0;implicitWidth:updateSheet.width
   Item{Layout.fillWidth:true;Layout.preferredHeight:132
    Rectangle{anchors.fill:parent;radius:24;gradient:Gradient{GradientStop{position:0;color:musicbox.darkMode?"#16324A":"#D6EEFF"}GradientStop{position:1;color:"transparent"}}}
    BrandMark{anchors.centerIn:parent;width:76;height:76;animated:updateSheet.opened}
   }
   ColumnLayout{Layout.fillWidth:true;Layout.margins:24;Layout.topMargin:6;spacing:12
    Label{Layout.fillWidth:true;horizontalAlignment:Text.AlignHCenter;wrapMode:Text.WordWrap;text:musicbox.updateState==="installing"?qsTr("Instalando actualización…"):qsTr("Nueva versión disponible");color:w.fg;font.pixelSize:21;font.bold:true}
    Label{Layout.fillWidth:true;horizontalAlignment:Text.AlignHCenter;wrapMode:Text.WordWrap;text:"MusicBox "+(musicbox.update.version||"")+qsTr("  ·  tienes la ")+musicbox.appVersion;color:w.muted;font.pixelSize:12}
    Label{visible:musicbox.updateState==="available"||musicbox.updateState==="error";text:qsTr("NOVEDADES");color:"#50E9FF";font.pixelSize:11;font.bold:true;font.letterSpacing:1.4;Layout.topMargin:6}
    ScrollView{id:notesScroll;visible:musicbox.updateState==="available"||musicbox.updateState==="error";Layout.fillWidth:true;Layout.preferredHeight:Math.min(updateSheet.detailed?260:190,notesCol.implicitHeight);clip:true;contentWidth:availableWidth
     Behavior on Layout.preferredHeight{NumberAnimation{duration:200;easing.type:Easing.OutCubic}}
     Column{id:notesCol;width:parent.width;spacing:8
      Repeater{model:updateSheet.detailed?updateSheet.allNotes:updateSheet.briefNotes
       delegate:Row{width:notesCol.width;spacing:10;Rectangle{width:6;height:6;radius:3;y:7;color:w.accent}Label{width:parent.width-16;text:modelData;color:w.fg;font.pixelSize:13;wrapMode:Text.WordWrap;lineHeight:1.15}}}
      Label{visible:updateSheet.allNotes.length===0;width:parent.width;text:qsTr("Mejoras de rendimiento y correcciones.");color:w.muted;font.pixelSize:13}
     }
    }
    Label{visible:(musicbox.updateState==="available"||musicbox.updateState==="error")&&updateSheet.hasMore;text:updateSheet.detailed?qsTr("Ver menos"):qsTr("Ver detalles");color:"#50E9FF";font.pixelSize:12;font.bold:true
     MouseArea{anchors.fill:parent;anchors.margins:-6;cursorShape:Qt.PointingHandCursor;onClicked:{updateSheet.detailed=!updateSheet.detailed;notesScroll.ScrollBar.vertical.position=0}}}
    Item{visible:musicbox.updateState==="downloading"||musicbox.updateState==="installing";Layout.fillWidth:true;Layout.preferredHeight:34
     ProgressBar{anchors.left:parent.left;anchors.right:parent.right;anchors.top:parent.top;value:musicbox.updateProgress;indeterminate:musicbox.updateState==="installing"}
     Label{anchors.horizontalCenter:parent.horizontalCenter;anchors.bottom:parent.bottom;text:musicbox.updateState==="installing"?qsTr("MusicBox se reiniciará solo"):Math.round(musicbox.updateProgress*100)+" %";color:w.muted;font.pixelSize:11}
    }
    Label{visible:musicbox.updateState==="error";Layout.fillWidth:true;text:musicbox.updateError;color:"#FF6B61";font.pixelSize:12;wrapMode:Text.WordWrap}
    RowLayout{Layout.fillWidth:true;Layout.topMargin:6;spacing:8;visible:musicbox.updateState!=="installing"
     Button{flat:true;text:musicbox.updateState==="downloading"?qsTr("Cancelar"):qsTr("Más tarde");onClicked:{updateSheet.close();if(musicbox.updateState==="downloading")musicbox.dismissUpdate()}}
     Button{flat:true;visible:musicbox.updateState==="available";text:qsTr("Omitir");Material.foreground:w.muted;onClicked:musicbox.skipUpdate()}
     Item{Layout.fillWidth:true}
     Button{visible:musicbox.updateState!=="downloading";highlighted:true;text:musicbox.updateState==="error"?qsTr("Reintentar"):qsTr("Actualizar ahora");onClicked:musicbox.installUpdate()}
    }
   }
  }
 }
 Rectangle{id:login;z:900;anchors.fill:parent;visible:!musicbox.authenticated;color:musicbox.darkMode?"#0E1116":"#F7F9FC"
  Column{anchors.horizontalCenter:parent.horizontalCenter;y:Math.round((parent.height-height)/2);width:360;spacing:18
   BrandMark{anchors.horizontalCenter:parent.horizontalCenter;width:92;height:92;animated:true}
   Text{anchors.horizontalCenter:parent.horizontalCenter;text:qsTr("Tu música, en todos lados");color:w.fg;font.pixelSize:25;font.bold:true}
   Text{width:parent.width;text:qsTr("Inicia sesión para sincronizar tu biblioteca, favoritos, playlists y preferencias.");color:w.muted;font.pixelSize:13;horizontalAlignment:Text.AlignHCenter;wrapMode:Text.WordWrap;lineHeight:1.3}
   Button{id:googleLogin;anchors.horizontalCenter:parent.horizontalCenter;width:260;height:50;enabled:!musicbox.authBusy;hoverEnabled:true;onClicked:musicbox.signInGoogle();contentItem:Item{implicitWidth:loginRow.implicitWidth;implicitHeight:loginRow.implicitHeight;Row{id:loginRow;anchors.centerIn:parent;spacing:12;Text{anchors.verticalCenter:parent.verticalCenter;text:"G";color:w.accent;font.pixelSize:20;font.bold:true}Text{anchors.verticalCenter:parent.verticalCenter;text:musicbox.authBusy?qsTr("Conectando…"):qsTr("Continuar con Google");color:w.fg;font.pixelSize:14;font.bold:true}}}background:Rectangle{radius:25;color:googleLogin.down?(musicbox.darkMode?"#252A32":"#DCE6F3"):(googleLogin.hovered?(musicbox.darkMode?"#20252D":"#E8F1FC"):w.panel);border.width:1;border.color:googleLogin.hovered?w.accent:(musicbox.darkMode?"#343A45":"#D5DCE6");Behavior on color{ColorAnimation{duration:140}}Behavior on border.color{ColorAnimation{duration:140}}}scale:down?.97:1;Behavior on scale{NumberAnimation{duration:100;easing.type:Easing.OutCubic}}}
   BusyIndicator{anchors.horizontalCenter:parent.horizontalCenter;running:musicbox.authBusy;visible:running;width:28;height:28}
   Text{visible:musicbox.authError.length>0;width:parent.width;text:musicbox.authError;color:"#FF453A";font.pixelSize:11;horizontalAlignment:Text.AlignHCenter;wrapMode:Text.WordWrap}
   Text{width:parent.width;text:qsTr("Las descargas permanecen únicamente en este dispositivo.");color:w.muted;font.pixelSize:10;horizontalAlignment:Text.AlignHCenter}
  }
 }
 NumberAnimation on splashPhase{from:0;to:1;duration:1650;loops:Animation.Infinite;easing.type:Easing.Linear;running:w.splashVisible}
 NumberAnimation on splashIntro{from:0;to:1;duration:650;easing.type:Easing.OutBack;running:true}
 Timer{interval:2100;running:true;repeat:false;onTriggered:w.splashVisible=false}
 Rectangle{id:splash;z:1000;anchors.fill:parent;visible:opacity>0;opacity:w.splashVisible?1:0;color:"#11161C";Behavior on opacity{NumberAnimation{duration:420;easing.type:Easing.InOutCubic}}
  MouseArea{anchors.fill:parent}
  Item{id:splashMark;anchors.centerIn:parent;width:Math.min(250,Math.min(parent.width,parent.height)*.42);height:width;opacity:w.splashIntro;transform:Translate{y:-5*Math.abs(Math.sin(w.splashPhase*Math.PI*2))}
   Image{anchors.fill:parent;source:"../assets/logo_cube.png";fillMode:Image.PreserveAspectFit;smooth:true;mipmap:true}
   Canvas{id:waveCanvas;anchors.fill:parent;onPaint:{var c=getContext("2d");c.reset();var raw=[[18,238],[32,245],[43,238],[56,192],[67,190],[83,282],[94,295],[120,122],[130,128],[148,361],[158,364],[177,244],[187,242],[201,302],[220,312]];var s=Math.min(width/446,height/488);var ox=(width-446*s)/2;var oy=(height-488*s)/2;var pts=[];for(var i=0;i<raw.length;i++){var t=i/(raw.length-1);var travel=Math.sin(w.splashPhase*Math.PI*2-raw[i][0]/446*Math.PI*4);var anchor=Math.sin(t*Math.PI);pts.push([ox+raw[i][0]*s,oy+(raw[i][1]+15*travel*anchor)*s])}c.beginPath();c.moveTo(pts[0][0],pts[0][1]);for(var j=1;j<pts.length-1;j++){var mx=(pts[j][0]+pts[j+1][0])/2;var my=(pts[j][1]+pts[j+1][1])/2;c.quadraticCurveTo(pts[j][0],pts[j][1],mx,my)}c.lineTo(pts[pts.length-1][0],pts[pts.length-1][1]);c.strokeStyle="#50E9FF";c.lineWidth=9*s;c.lineCap="round";c.lineJoin="round";c.shadowColor="#20BFFF";c.shadowBlur=12*s;c.stroke()}Connections{target:w;function onSplashPhaseChanged(){waveCanvas.requestPaint()}}}
  }
 }
}
