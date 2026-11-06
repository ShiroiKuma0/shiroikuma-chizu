import csv, sys, collections, statistics, glob, os
# usage: speeds.py <dir with chizu-roads_*.tsv and chizu-drive_*.txt> [ChizuSpeeds.java]
rows = {}
src = open(sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../../OsmAnd/src/net/osmand/plus/chizu/ChizuSpeeds.java')).read()
import re
H = {'MOTORWAY':['motorway'],'TRUNK':['trunk'],'PRIMARY':['primary'],'SECONDARY':['secondary'],'TERTIARY':['tertiary'],
     'UNCLASSIFIED':['unclassified'],'RESIDENTIAL':['residential'],'LIVING':['living_street'],'SERVICE':['service'],
     'MOTORWAY_LINK':['motorway_link'],'TRUNK_LINK':['trunk_link'],'OTHER_LINKS':['primary_link','secondary_link','tertiary_link']}
table=[]
for m in re.finditer(r'new Row\((\d+), "[^"]*", (\w+), (true|false), (NONE|new int\[\] \{([\d, ]+)\}), (\d+), (\d+)\)', src):
    no=int(m.group(1)); hw=H[m.group(2)]; tun=m.group(3)=='true'; lim=[int(x) for x in m.group(5).split(',')] if m.group(5) else []
    table.append((no,hw,tun,lim,int(m.group(6)),int(m.group(7))))
def row_for(h,t,l):
    # exact row, else None (interpolated)
    for no,hw,tun,lim,os_,pre in table:
        if h in hw and tun==t and ((l==0 and not lim) or (l in lim)): return no
    if t:
        return row_for(h,False,l)
    return None
# simulated drives: from 'test sim on' to 'test sim off', an app start or an arrival
sims=collections.defaultdict(list)
for f in glob.glob(os.path.join(sys.argv[1], 'chizu-drive_*.txt')):
    day=os.path.basename(f)[12:22]; on=None
    for line in open(f, encoding='utf-8', errors='replace'):
        t=line[:8]
        if 'test sim on' in line: on=t
        elif on and ('test sim off' in line or '] start 白い熊 地図' in line or line.rstrip().endswith('] arrived')):
            sims[day].append((on,t)); on=None
    if on: sims[day].append((on,'99:99:99'))
def simulated(day,t): return any(a<=t<=b for a,b in sims.get(day,[]))
data=collections.defaultdict(list)
rows_in=[]
for f in sorted(glob.glob(os.path.join(sys.argv[1], 'chizu-roads_*.tsv'))):
    rows_in += [r for r in csv.reader(open(f, encoding='utf-8'), delimiter='\t') if r and r[0] != 'day']
for r in rows_in:
    if len(r)>=22 and simulated(r[0], r[2]): continue
    if len(r)<22 or r[4]!='section': continue
    h=r[5]; 
    if h=='unmatched': continue
    t=r[6]=='1'; l=int(r[8] or 0); col=r[10]; L=float(r[11]); T=float(r[12]); short=r[20]=='1'
    if short or T<=0 or L<50: continue
    data[(h,t,l)].append((col,L,T,float(r[13]),float(r[14]),int(r[15]),int(r[16]),r[0]))
order={'motorway':0,'trunk':1,'primary':2,'secondary':3,'tertiary':4,'unclassified':5,'residential':6,'living_street':7,'service':8}
print("row\thighway\ttun\tlimit\tosmand\tmine_now\tfree_n\tfree_km\tfree_kmh\tfree_p85\tall_kmh\tjam_km\tdays\tsuggest")
for k in sorted(data, key=lambda k:(order.get(k[0],9),k[1],-k[2])):
    v=data[k]; free=[x for x in v if x[0] in ('green','grey')]
    fl=sum(x[1] for x in free); ft=sum(x[2] for x in free)
    al=sum(x[1] for x in v); at=sum(x[2] for x in v)
    jam=sum(x[1] for x in v if x[0] in ('red','dark_red','yellow'))
    p85 = sorted(x[3] for x in free)[int(0.85*(len(free)-1))] if free else 0
    mine=collections.Counter(x[6] for x in v).most_common(1)[0][0]; osm=collections.Counter(x[5] for x in v).most_common(1)[0][0]
    days=len(set(x[7] for x in v))
    # first guess only, to be judged: between the average (lights stop it) and the 85th percentile
    sug = '' if fl < 1000 else str(int(5*round(((fl/ft*3.6)*0.6 + p85*0.4)/5)))
    print(f"{row_for(*k)}\t{k[0]}\t{int(k[1])}\t{k[2]}\t{osm}\t{mine}\t{len(free)}\t{fl/1000:.1f}\t{(fl/ft*3.6 if ft else 0):.0f}\t{p85:.0f}\t{al/at*3.6:.0f}\t{jam/1000:.1f}\t{days}\t{sug}")
