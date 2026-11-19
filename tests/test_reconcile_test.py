import importlib.machinery
import importlib.util
from pathlib import Path
from unittest.mock import patch
import unittest

FILE=Path(__file__).resolve().parent.parent/'scripts/reconcile-test'
loader=importlib.machinery.SourceFileLoader('awfl_reconcile_test', str(FILE))
spec=importlib.util.spec_from_loader(loader.name,loader)
mod=importlib.util.module_from_spec(spec)
loader.exec_module(mod)

class ReconcileLauncherTest(unittest.TestCase):
    def test_missing_server_fails_doctor(self):
        self.assertEqual(mod.main(['doctor','--server','/tmp/no-reconcile-server','--compiler','/tmp/no-compiler']),1)
    def test_argument_validation_rejects_unknown(self):
        with self.assertRaises(SystemExit):
            mod.main(['bad-command'])
    def test_postgres_requires_explicit_isolated_url(self):
        with patch.dict('os.environ', {'PGTEST_URL':''}):
            with self.assertRaises(SystemExit) as raised:
                mod.main(['postgres'])
        self.assertIn('PGTEST_URL is required',str(raised.exception))

    def test_postgres_rejects_remote_databases_before_any_sql(self):
        with patch.dict('os.environ', {'PGTEST_URL':'postgres://admin@production-db.example:5432/awfl_prod'}):
            with self.assertRaises(SystemExit) as raised:
                mod.main(['postgres'])
        self.assertIn('Refusing PostgreSQL tests outside loopback',str(raised.exception))

    def test_typed_ista_requires_local_database(self):
        with patch.dict('os.environ', {'PGTEST_URL':''}):
            with self.assertRaises(SystemExit) as raised:
                mod.main(['typed-ista'])
        self.assertIn('PGTEST_URL is required',str(raised.exception))

    def test_typed_ista_refuses_remote_database_before_compile(self):
        with patch.dict('os.environ', {'PGTEST_URL':'postgres://admin@db.example.com/awfl_reconcile'}):
            with self.assertRaises(SystemExit) as raised:
                mod.main(['typed-ista'])
        self.assertIn('Refusing typed-ista test outside loopback',str(raised.exception))

    def test_durable_requires_compiled_report(self):
        with self.assertRaises(SystemExit) as raised:
            mod.main(['durable','--report','/tmp/missing-reconcile-report.json'])
        self.assertIn('Native build report absent',str(raised.exception))

    def test_missing_report_is_actionable(self):
        with self.assertRaises(SystemExit) as raised:
            mod.main(['basic','--server','/tmp/no-reconcile-server'])
        self.assertIn('Missing server',str(raised.exception))

if __name__=='__main__':unittest.main()
