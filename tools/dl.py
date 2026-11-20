import json,urllib.request,urllib.parse,time,os
UA={'User-Agent':'SalahTrackerApp/1.0 (github.com/saleh-a11y/Salah-App; photo download)'}
os.makedirs('full',exist_ok=True)
for k,it in json.load(open('tools/chosen.json')).items():
    t=it['title'].replace('File:','')
    u='https://commons.wikimedia.org/w/api.php?'+urllib.parse.urlencode(dict(action='query',titles='File:'+t,prop='imageinfo',iiprop='url',iiurlwidth=1800,format='json'))
    time.sleep(2)
    d=json.loads(urllib.request.urlopen(urllib.request.Request(u,headers=UA),timeout=60).read())
    ii=list(d['query']['pages'].values())[0]['imageinfo'][0]
    time.sleep(2)
    data=urllib.request.urlopen(urllib.request.Request(ii['thumburl'],headers=UA),timeout=120).read()
    open(f'full/{k}.jpg','wb').write(data);print(k,len(data))
