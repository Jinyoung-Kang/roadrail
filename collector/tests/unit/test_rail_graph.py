from roadrail.analytics.rail_graph import RailGraph, hav, simplify


def way(ids, pts):
    return {"nodes": ids, "geometry": [{"lat": a, "lon": b} for a, b in pts]}


# 두 선로: 본선 A(1→2→3→4, 동쪽으로) 와 역 근처에서 갈라지는 지선 B(10→11)
WAYS = [way([1, 2, 3, 4], [(37.0, 127.00), (37.0, 127.05), (37.0, 127.10), (37.0, 127.15)]),
        way([2, 10, 11], [(37.0, 127.05), (37.05, 127.05), (37.10, 127.05)])]


def test_routes_follow_track_and_station_snaps_to_all_nearby_nodes():
    g = RailGraph(WAYS)
    assert g.add_station("W", 37.001, 127.001)
    assert g.add_station("E", 37.001, 127.149)
    assert g.add_station("N", 37.101, 127.051)
    r = g.routes_from("W", ["E", "N"])
    length_e, path_e = r["E"]
    assert abs(length_e - hav((37.0, 127.0), (37.0, 127.15))) < 0.5   # 본선을 따라감
    assert path_e[0] == (37.001, 127.001) and path_e[-1] == (37.001, 127.149)
    assert (37.0, 127.05) in path_e and (37.05, 127.05) not in path_e   # 지선으로 새지 않음
    length_n, path_n = r["N"]
    assert (37.0, 127.05) in path_n and (37.05, 127.05) in path_n      # 분기점에서 지선으로


def test_station_far_from_track_is_not_snapped_and_other_station_is_not_a_shortcut():
    g = RailGraph(WAYS)
    assert not g.add_station("X", 36.0, 126.0)
    assert g.routes_from("X", ["E"]) == {}
    g.add_station("W", 37.0, 127.0)
    g.add_station("M", 37.0, 127.10)
    g.add_station("E", 37.0, 127.15)
    # W→E 경로는 M 역의 가상 노드를 거치지 않고 선로 노드로만 간다
    _, path = g.routes_from("W", ["E"])["E"]
    assert path.count((37.0, 127.10)) == 1


def test_simplify_keeps_shape_drops_collinear_points():
    line = [(37.0, 127.0 + i * 0.001) for i in range(50)]
    assert simplify(line) == [line[0], line[-1]]
    bent = line + [(37.01, 127.049)]
    assert len(simplify(bent)) == 3


def test_osm_tile_pack_roundtrip_keeps_only_ways(tmp_path):
    from roadrail.providers import osm
    elements = [{"type": "way", "id": 1, "nodes": [1, 2], "geometry": [{"lat": 37.0, "lon": 127.0}, {"lat": 37.1, "lon": 127.1}]},
                {"type": "node", "id": 3}, {"type": "way", "id": 2, "nodes": [], "geometry": []}]
    ways = osm.compact(elements)
    assert [w["id"] for w in ways] == [1]
    assert osm.unpack(osm.pack(ways)) == ways
    f = tmp_path / "osm.json"
    f.write_text(__import__("json").dumps({"elements": elements}))
    assert osm.ways_from_file(f) == ways
