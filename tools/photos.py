import json,urllib.request,urllib.parse,io,os,re,sys
from PIL import Image,ImageDraw
UA={'User-Agent':'SalahTrackerApp/1.0 (github.com/saleh-a11y/Salah-App; photo selection)'}
Q={'rawdah':['Green Dome Prophet\'s Mosque Medina','Al-Masjid an-Nabawi green dome'],
   'kiswah':['Kaaba Masjid al-Haram','Kaaba Mecca'],
   'nabawi':['Prophet\'s Mosque Medina night','Masjid Nabawi night umbrellas'],
   'alhambra':['Alhambra Court of the Myrtles','Alhambra Patio de los Arrayanes'],
   'iznik':['Sultan Ahmed Mosque exterior','Blue Mosque Istanbul'],
   'isfahan':['Shah Mosque Isfahan','Sheikh Lotfollah Mosque'],
   'mughal':['Taj Mahal','Taj Mahal sunrise']}
OK=re.compile(r'^(cc0|public domain|pd|cc by(-sa)? ?[1-4]\.0|cc by-sa [1-4]\.0|cc by [1-4]\.0)',re.I)
def get(u):return urllib.request.urlopen(urllib.request.Request(u,headers=UA),timeout=60).read()
os.makedirs('cand',exist_ok=True)
meta={}
for k,qs in Q.items():
    seen=[];items=[]
    for q in qs:
        u='https://commons.wikimedia.org/w/api.php?'+urllib.parse.urlencode(dict(action='query',generator='search',gsrnamespace=6,gsrsearch=q+' filetype:bitmap',gsrlimit=14,prop='imageinfo',iiprop='url|extmetadata|size|mime',iiurlwidth=640,format='json'))
        try:d=json.loads(get(u))
        except Exception as e:print('ERR',k,e);continue
        for p in sorted(d.get('query',{}).get('pages',{}).values(),key=lambda x:x.get('index',0)):
            ii=(p.get('imageinfo') or [{}])[0];em=ii.get('extmetadata',{})
            lic=em.get('LicenseShortName',{}).get('value','')
            if p['title'] in seen or ii.get('mime')!='image/jpeg' or ii.get('width',0)<1600 or not OK.match(lic.strip()):continue
            if ii['width']<ii['height']*1.1:continue   # landscape only
            seen.append(p['title'])
            artist=re.sub('<[^>]+>','',em.get('Artist',{}).get('value','')).strip()[:80]
            items.append(dict(title=p['title'],thumb=ii['thumburl'],w=ii['width'],h=ii['height'],lic=lic,artist=artist,page=ii.get('descriptionurl')))
    items=items[:12];meta[k]=items
    th=[]
    for i,it in enumerate(items):
        try:im=Image.open(io.BytesIO(get(it['thumb']))).convert('RGB');im.thumbnail((320,220));th.append(im)
        except Exception as e:th.append(Image.new('RGB',(320,220),'gray'))
    S=Image.new('RGB',(4*330,3*250),'white');d=ImageDraw.Draw(S)
    for i,im in enumerate(th):
        x,y=(i%4)*330,(i//4)*250;S.paste(im,(x+5,y+5));d.rectangle([x+5,y+5,x+30,y+25],fill='black');d.text((x+10,y+8),str(i),fill='white')
    S.save(f'cand/{k}.jpg',quality=80)
json.dump(meta,open('cand/meta.json','w'),indent=1)
print({k:len(v) for k,v in meta.items()})
