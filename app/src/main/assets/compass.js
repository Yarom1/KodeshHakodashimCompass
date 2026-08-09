// ===== יעד: קודש הקודשים =====
const TARGET_LAT = 31.7779;
const TARGET_LNG = 35.2354;
const ON_TARGET_THRESHOLD = 5; // מעלות

let myLat = null, myLng = null;
let lastHeading = 0;
let lastAbsolute = false;
let isUpright = false;

// זוויות תצוגה מצטברות - מונעות קפיצת 360°→0° בסיבוב ה-CSS
let displayedFlat = 0;
let displayedAr = 0;

// ---------- רקע כוכבים ----------
(function makeStars(){
  const c = document.getElementById('stars');
  for(let i=0;i<60;i++){
    const s=document.createElement('div');
    s.className='star';
    s.style.left=Math.random()*100+'%';
    s.style.top=Math.random()*100+'%';
    s.style.opacity=(Math.random()*0.6+0.2).toFixed(2);
    c.appendChild(s);
  }
})();

// ---------- bearing / distance ----------
function toRad(d){return d*Math.PI/180;}
function toDeg(r){return r*180/Math.PI;}

function calcBearing(lat1,lng1,lat2,lng2){
  const φ1=toRad(lat1), φ2=toRad(lat2), Δλ=toRad(lng2-lng1);
  const y=Math.sin(Δλ)*Math.cos(φ2);
  const x=Math.cos(φ1)*Math.sin(φ2)-Math.sin(φ1)*Math.cos(φ2)*Math.cos(Δλ);
  let brng=toDeg(Math.atan2(y,x));
  return (brng+360)%360;
}

function calcDistanceKm(lat1,lng1,lat2,lng2){
  const R=6371;
  const φ1=toRad(lat1), φ2=toRad(lat2);
  const dφ=toRad(lat2-lat1), dλ=toRad(lng2-lng1);
  const a=Math.sin(dφ/2)**2+Math.cos(φ1)*Math.cos(φ2)*Math.sin(dλ/2)**2;
  return R*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
}

// ---------- אלגוריתם המצפן (מ-Location Share) ----------
function computeFullCompassHeading(alpha,beta,gamma){
  const aR=alpha*(Math.PI/180),bR=beta*(Math.PI/180),gR=gamma*(Math.PI/180);
  const cA=Math.cos(aR),sA=Math.sin(aR);
  const cB=Math.cos(bR),sB=Math.sin(bR);
  const cG=Math.cos(gR),sG=Math.sin(gR);
  const Vx=-cA*sG-sA*sB*cG;
  const Vy=-sA*sG+cA*sB*cG;
  let heading=Math.atan2(Vx,Vy)*(180/Math.PI);
  if(heading<0)heading+=360;
  return heading;
}

function getDeviceHeading(e){
  if(e.webkitCompassHeading!=null) return e.webkitCompassHeading;
  if(e.alpha!=null&&e.beta!=null&&e.gamma!=null){
    if(Math.abs(e.beta)<15&&Math.abs(e.gamma)<15){
      return (360-e.alpha)%360;
    }
    return computeFullCompassHeading(e.alpha,e.beta,e.gamma);
  }
  return null;
}

// ---------- מעבר שכיבה/עמידה ----------
function handleMotion(e){
  const g=e.accelerationIncludingGravity;
  if(!g||g.z==null)return;
  const flat = Math.abs(g.z) > 6.5;
  if(flat===!isUpright)return;
  isUpright = !flat;
  document.getElementById('uprightView').style.opacity = isUpright?'1':'0';
  document.getElementById('uprightView').style.pointerEvents = isUpright?'auto':'none';
  document.getElementById('flatCompass').style.opacity = isUpright?'0':'1';
}

// ---------- עוזר: מסלול הסיבוב הקצר ביותר (מונע קפיצה סביב 360°) ----------
function shortestDelta(from, to){
  let d = ((to - from) % 360 + 540) % 360 - 180;
  return d;
}

// ---------- עדכון תצוגה ----------
function updateDisplay(heading){
  if(myLat==null)return;
  const bearing = calcBearing(myLat,myLng,TARGET_LAT,TARGET_LNG);
  const relative = ((bearing - heading)+360)%360;

  // מקדמים את הזווית המצטברת בהפרש הקצר ביותר, לא "קופצים" לערך הגולמי
  displayedFlat += shortestDelta(displayedFlat % 360, relative);
  displayedAr += shortestDelta(displayedAr % 360, relative);

  document.getElementById('flatDial').style.transform = `rotate(${displayedFlat}deg)`;
  document.getElementById('arArrow').style.transform = `rotate(${displayedAr}deg)`;

  const diff = Math.min(relative, 360-relative);
  document.getElementById('app').classList.toggle('on-target', diff <= ON_TARGET_THRESHOLD);

  const dist = calcDistanceKm(myLat,myLng,TARGET_LAT,TARGET_LNG);
  document.getElementById('distKm').textContent = dist<1
    ? Math.round(dist*1000)+' מ׳'
    : dist.toFixed(1)+' ק"מ';
}

function handleOrientation(e){
  if(e.type==='deviceorientation'&&lastAbsolute)return;
  lastAbsolute=(e.type==='deviceorientationabsolute');
  const h=getDeviceHeading(e);
  if(h==null)return;
  if(Math.abs(h-lastHeading)>1){
    lastHeading=h;
    updateDisplay(h);
  }
}

// ---------- מיקום ----------
function startLocation(){
  navigator.geolocation.watchPosition(pos=>{
    myLat=pos.coords.latitude;
    myLng=pos.coords.longitude;
    document.getElementById('status').textContent='מכוון...';
    updateDisplay(lastHeading);
  },err=>{
    document.getElementById('status').textContent='אין גישה למיקום';
  },{enableHighAccuracy:true,maximumAge:5000,timeout:15000});
}

// ---------- אתחול ----------
function init(){
  window.addEventListener('deviceorientationabsolute',handleOrientation,true);
  window.addEventListener('deviceorientation',handleOrientation,true);
  window.addEventListener('devicemotion',handleMotion,true);
  startLocation();
}

init();
