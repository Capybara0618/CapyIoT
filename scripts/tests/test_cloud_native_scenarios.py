import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import cloud_native_scenarios as scenarios


class FaultEvidenceBoundaries(unittest.TestCase):
    def test_kafka_is_unpaused_when_the_observation_fails(self):
        target=SimpleNamespace(state=lambda:{"containers":{"kafka":{"containerId":"bound-kafka"}}})
        with patch.object(scenarios,"verified_endpoints"),patch.object(scenarios,"offsets",return_value="offsets"), \
                patch.object(scenarios.cn,"execute") as execute,patch.object(scenarios.time,"sleep",side_effect=RuntimeError("interrupted")):
            with self.assertRaises(RuntimeError): scenarios.kafka_recovery(target,Path("unused"))
        self.assertEqual(execute.call_args.args[0],["docker","unpause","bound-kafka"])

    def test_metrics_report_cannot_be_manufactured_from_empty_samples(self):
        with self.assertRaises(ValueError): scenarios.distribution([])
