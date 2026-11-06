import unittest
from bridge import project_rows

class MessageTimesTest(unittest.TestCase):
    def test_projects_only_valid_native_creation_time(self):
        for stamp in (1793896200000, 1793896200000.5):
            item = project_rows([{'rowId': 1, 'kind': 'userInput', 'text': 'test', 'createdAt': stamp}])[0]
            self.assertEqual(item['createdAt'], int(stamp))
        for stamp in (None, True, 0, -1, float('nan'), float('inf'), '2026-11-06', 10**30):
            item = project_rows([{'rowId': 1, 'kind': 'assistantText', 'text': 'test', 'createdAt': stamp}])[0]
            self.assertNotIn('createdAt', item)
        self.assertNotIn('createdAt', project_rows([{'rowId': 1, 'kind': 'assistantText'}])[0])
