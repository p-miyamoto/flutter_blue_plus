part of flutter_blue_plus;

String _hexEncode(List<int> numbers) {
  return numbers
      .map((n) => (n & 0xFF).toRadixString(16).padLeft(2, '0'))
      .join();
}

List<int> _hexDecode(String hex) {
  List<int> numbers = [];
  for (int i = 0; i < hex.length; i += 2) {
    String hexPart = hex.substring(i, i + 2);
    int num = int.parse(hexPart, radix: 16);
    numbers.add(num);
  }
  return numbers;
}