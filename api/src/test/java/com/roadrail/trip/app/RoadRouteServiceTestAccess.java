package com.roadrail.trip.app;

import com.roadrail.external.KakaoMobilityClient;
import com.roadrail.trip.model.RouteDtos;

/** 패키지 전용 summarize 를 domain 테스트에서 쓰기 위한 통로 */
public final class RoadRouteServiceTestAccess {
    private RoadRouteServiceTestAccess() {}

    public static RouteDtos.RouteSummary summarize(KakaoMobilityClient.Route r) {
        return RoadRouteService.summarize("테스트", r);
    }
}
