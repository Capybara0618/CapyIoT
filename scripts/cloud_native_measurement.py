"""Adapt the retained protocol/ACK/SQL measurement to the bound Kubernetes target."""

import cloud_native_common as cn
from cloud_native_environment import verified_endpoints
from microservices_control import db


class MeasurementTarget:
    def __init__(self, **selection):
        self.target = cn.Target(**selection)
        self.manifest = self.target.manifest
        self.project = cn.INFRA_PROJECT
        self.values = {"MS_MQTT_PORT": "28883"}

    def preflight(self):
        verified_endpoints(self.target)

    def sql(self, statement):
        return db(self.target.state()["containers"]["mysql"]["containerId"], statement)

    def compose(self, *args):
        if args[:3] != ("exec", "-T", "device-ingestion"):
            raise ValueError("Measurement adapter permits only ingestion metrics reads")
        return self.target.kubectl("exec", "deployment/device-ingestion", "--", *args[3:])
