import copy
import unittest
from summarize_baseline_scheduling import validate_request


class SchedulingValidationTest(unittest.TestCase):
    def setUp(self):
        self.values = dict(request_submitted=10, coordinator_start=11, backend_start=12,
                           planning_start=13, planning_end=15, first_task_submitted=16,
                           first_worker_start=17, cancel_requested=18, cancel_observed=19,
                           backend_exit=20, coordinator_exit=21, last_worker_exit=30, request_drained=31,
                           worker_tasks_registered=2, worker_tasks_submitted=2, worker_tasks_started=1,
                           worker_tasks_cancelled_before_start=1, worker_tasks_terminal=2,
                           worker_queue_sum_ns=1, worker_queue_max_ns=1)

    def test_worker_exit_after_backend_is_valid(self):
        validate_request(self.values)

    def test_rejects_false_drain_and_invalid_accounting(self):
        for key, value in (("request_drained", 25), ("worker_tasks_terminal", 1),
                           ("worker_tasks_cancelled_before_start", 0), ("worker_tasks_submitted", 0),
                           ("worker_queue_sum_ns", 0), ("cancel_requested", 22)):
            with self.subTest(key=key):
                values = copy.copy(self.values)
                values[key] = value
                with self.assertRaises(ValueError):
                    validate_request(values)

    def test_queued_coordinator_does_not_require_backend_or_workers(self):
        validate_request(dict(request_submitted=1, cancel_requested=2,
                              coordinator_cancelled_before_start=3, request_drained=4, worker_tasks_terminal=0))

    def test_complete_reuse_can_have_zero_tasks(self):
        validate_request(dict(request_submitted=1, coordinator_start=2, backend_start=3,
                              planning_start=4, planning_end=20, backend_exit=21, coordinator_exit=22,
                              request_drained=23, planned_tasks=0, worker_tasks_terminal=0, mask_scan_ns=15))


if __name__ == "__main__":
    unittest.main()
