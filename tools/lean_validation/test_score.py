import unittest

from score import score


def fixture():
    refs, estimates = [], []
    for group in ('low_speed', 'flowing'):
        for i in range(40):
            start = len(refs) * 5000
            row = dict(startMs=start, endMs=start + 4500, signedPeakDegrees=(-1)**i * 15.5)
            refs.append(dict(kind=group, **row))
            if i < 20:
                estimates.append(row.copy())
    refs.append(dict(startMs=400000, endMs=420000, kind='parked_steering'))
    return dict(source='synthetic', tuningRideIds=['development'],
                rides=[dict(id='held-out', reference=refs, reported=estimates)])


class FieldScoringTest(unittest.TestCase):
    def test_exact_boundary_and_synthetic_label(self):
        data = fixture()
        data['rides'][0]['reported'][0]['signedPeakDegrees'] += 4
        result = score(data)
        self.assertTrue(result['numericCriteriaMet'])  # 19/20 accurate, 20/40 covered
        self.assertEqual(result['classes']['low_speed']['coveragePercent'], 50)
        self.assertEqual(result['classes']['low_speed']['within3DegreesPercent'], 95)
        self.assertEqual(result['fieldStatus'], 'SYNTHETIC_ONLY')

    def test_strata_cannot_mask_error_or_missing_coverage(self):
        data = fixture()
        for index in (0, 1):
            data['rides'][0]['reported'][index]['signedPeakDegrees'] *= 1.5
        self.assertFalse(score(data)['classes']['low_speed']['meetsTargets'])
        data = fixture()
        data['rides'][0]['reported'].pop()
        self.assertFalse(score(data)['classes']['flowing']['meetsTargets'])

    def test_wrong_sign_and_parked_false_positive_fail(self):
        data = fixture()
        data['rides'][0]['reported'][0]['signedPeakDegrees'] *= -1
        reversed_peak = score(data)
        self.assertFalse(reversed_peak['numericCriteriaMet'])
        self.assertEqual(reversed_peak['classes']['low_speed']['within3Degrees'], 19)
        data = fixture()
        data['rides'][0]['reported'].append(dict(startMs=400001, endMs=402001, signedPeakDegrees=7))
        result = score(data)
        self.assertEqual(result['reportsDuringParkedSteering'], 1)
        self.assertFalse(result['numericCriteriaMet'])

    def test_independent_rides_and_no_double_count(self):
        data = fixture()
        data['tuningRideIds'].append('held-out')
        with self.assertRaises(ValueError):
            score(data)
        data = fixture()
        estimates = data['rides'][0]['reported']
        estimates[0]['endMs'] = 2250
        estimates.insert(1, dict(startMs=2250, endMs=4500, signedPeakDegrees=15.5))
        result = score(data)
        self.assertEqual(result['unmatchedReports'], 1)
        self.assertEqual(result['classes']['low_speed']['matched'], 20)
        self.assertFalse(result['numericCriteriaMet'])


if __name__ == '__main__':
    unittest.main()
