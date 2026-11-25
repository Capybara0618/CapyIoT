class ServiceError(Exception):
    def __init__(self, code: str, status: int, message: str, details=None):
        self.code, self.status, self.message = code, status, message
        self.details = details or {}
        super().__init__(code)


def unavailable(code="UPSTREAM_UNAVAILABLE"):
    return ServiceError(code, 503, "依赖服务暂不可用，请稍后重试")
