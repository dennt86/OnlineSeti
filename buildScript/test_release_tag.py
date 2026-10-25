import unittest
from release_tag import release_build_number, version_key


class ReleaseCodeTest(unittest.TestCase):
    def test_prereleases_and_stable_increase(self):
        versions = ["1.4.2-beta.1", "1.4.2-beta.2", "1.4.2-beta.10", "1.4.2-rc.1", "1.4.2"]
        codes = [release_build_number(v) for v in versions]
        self.assertEqual(codes, [1, 2, 10, 500, 999])
        self.assertEqual(codes, sorted(set(codes)))

    def test_beta3_exceeds_legacy_beta_codes(self):
        self.assertGreater(36 * 1000 + release_build_number("1.4.2-beta.3"), 35999)

    def test_out_of_range_is_rejected(self):
        with self.assertRaises(ValueError):
            release_build_number("1.4.2-beta.500")

    def test_legacy_alpha_tag_sorts_before_beta_and_stable(self):
        alpha = version_key("v0.1.0-alpha.1")
        self.assertEqual(version_key("v0.1.0-alpha"), version_key("v0.1.0-alpha.0"))
        beta = version_key("0.1.0-beta.1")
        stable = version_key("0.1.0")
        self.assertLess(alpha, beta)
        self.assertLess(beta, stable)


if __name__ == "__main__":
    unittest.main()
