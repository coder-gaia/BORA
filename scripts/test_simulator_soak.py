"""Falhas de diagnóstico que precisam invalidar a qualificação do simulador."""

import copy
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "simulator_soak", Path(__file__).with_name("check-simulator-soak.py")
)
soak = importlib.util.module_from_spec(spec)
spec.loader.exec_module(soak)


class SimulatorSoakDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.database = dict.fromkeys(soak.INVARIANTS, 0)
        self.database.update(couriers=120, online=120, fresh=120, created=40, delivered=12)
        self.simulator = {
            "status": "UP", "couriersOnline": 120,
            "counters": {"http200": 1000, "http409": 2, "stateConflicts": 2},
        }

    def test_expected_races_do_not_invalidate_healthy_flow(self):
        self.assertEqual([], soak.violations(self.database, self.simulator, 120))

    def test_one_broken_reservation_or_privacy_invariant_invalidates_run(self):
        for key in soak.INVARIANTS:
            with self.subTest(key=key):
                database = {**self.database, key: 1}
                self.assertTrue(soak.violations(database, self.simulator, 120))

    def test_missing_or_stale_courier_invalidates_run(self):
        for key in ("couriers", "online", "fresh"):
            with self.subTest(key=key):
                database = {**self.database, key: 119}
                self.assertTrue(soak.violations(database, self.simulator, 120))

    def test_recovered_http_or_transport_failure_still_invalidates_run(self):
        for key in ("http500", "http503", "http507", "apiUnavailable", "networkFailures",
                    "clockRefreshFailures", "fatalActorFailures"):
            with self.subTest(key=key):
                simulator = copy.deepcopy(self.simulator)
                simulator["counters"][key] = 1
                self.assertTrue(soak.violations(self.database, simulator, 120))

    def test_partial_or_malformed_diagnostics_cannot_be_accepted(self):
        for value in (None, -1, True, "120"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                soak.violations({**self.database, "fresh": value}, self.simulator, 120)
        simulator = {key: value for key, value in self.simulator.items() if key != "counters"}
        with self.assertRaises(ValueError):
            soak.violations(self.database, simulator, 120)

    def test_simulator_and_database_must_both_report_all_couriers(self):
        for changes in ({"status": "DOWN"}, {"couriersOnline": 119}):
            with self.subTest(changes=changes):
                self.assertTrue(soak.violations(self.database, {**self.simulator, **changes}, 120))

    def test_recovered_background_failure_remains_visible_without_saving_raw_logs(self):
        output = (
            "INFO Application started\n"
            "WARN Não foi possível atualizar o progresso do pedido private-order-id\n"
            "WARN Delivery progress failed; database reconciliation remains active.\n"
        )
        counts = soak.log_failures(output)
        self.assertEqual(2, sum(counts.values()))
        self.assertNotIn("private-order-id", str(counts))
        self.assertEqual(0, sum(soak.log_failures("INFO A entrega foi concluída").values()))

    def test_unhandled_scheduler_failure_cannot_leave_a_false_success(self):
        counts = soak.log_failures(
            "2026-10-19 ERROR 1 --- Unexpected error occurred in scheduled task: private-detail"
        )
        self.assertGreater(sum(counts.values()), 0)
        self.assertNotIn("private-detail", str(counts))


if __name__ == "__main__":
    unittest.main()
