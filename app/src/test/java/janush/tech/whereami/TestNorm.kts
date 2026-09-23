package janush.tech.whereami
fun main() {
    val res1 = RoadNameNormalizer.normalize("Krakowska", "52", "85B")
    println("1: " + res1 + "")
    val res2 = RoadNameNormalizer.normalize("Krakowska", "DK52", "85B")
    println("2: " + res2 + "")
}
