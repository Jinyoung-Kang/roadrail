/** 빌드 때 고정되는 기능 스위치 (NEXT_PUBLIC_* 는 빌드 시점에 인라인된다) */

/** API 문서(Swagger UI) 링크 — API 의 SWAGGER_ENABLED 와 같은 값(compose 가 빌드 인자로 넘김). 꺼져 있으면 /docs 는 404 라 링크를 숨긴다 */
export const API_DOCS = process.env.NEXT_PUBLIC_API_DOCS === "true";
