object shuffle {
  import scala.util.Random
  val r = new Random(42)                          //> r  : scala.util.Random = scala.util.Random@418c56d
  val nums:Seq[Int] = (0 until 198431).toList     //> nums  : Seq[Int] = List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
                                                  //| , 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34
                                                  //| , 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53
                                                  //| , 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72
                                                  //| , 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91
                                                  //| , 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 10
                                                  //| 8, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123
                                                  //| , 124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138,
                                                  //|  139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 
                                                  //| 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 1
                                                  //| 69, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 18
                                                  //| 4, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199
                                                  //| , 200, 201, 202, 203, 20
                                                  //| Output exceeds cutoff limit.
  
  val x = r.shuffle(nums)                         //> x  : Seq[Int] = List(186074, 110192, 150106, 133825, 185976, 10274, 10801, 1
                                                  //| 3689, 57038, 40992, 35125, 129260, 13864, 98940, 49586, 164100, 171129, 6291
                                                  //| 1, 66051, 1278, 115149, 133840, 166841, 136841, 101876, 128014, 5501, 12492,
                                                  //|  185515, 141320, 29733, 11683, 97228, 96718, 72083, 18326, 80417, 158689, 11
                                                  //| 6776, 125791, 150283, 183145, 161506, 71596, 59214, 50438, 166699, 115236, 1
                                                  //| 52916, 639, 143793, 22243, 171997, 93512, 52189, 192047, 130742, 16708, 1850
                                                  //| 88, 147670, 182724, 129695, 181546, 91130, 129496, 116465, 39487, 108478, 15
                                                  //| 9615, 72080, 158495, 24468, 136572, 89791, 80679, 109518, 172958, 123473, 99
                                                  //| 055, 77409, 80812, 70810, 83674, 159840, 169800, 175438, 93123, 149746, 1362
                                                  //| 66, 90050, 87849, 50575, 169905, 78390, 104439, 154613, 110222, 87850, 5802,
                                                  //|  86379, 108307, 14815, 186875, 79839, 75722, 127383, 179248, 72603, 165913, 
                                                  //| 51983, 104022, 68137, 111285, 69911, 31962, 8152, 196449, 62799, 41725, 1834
                                                  //| 75, 81534, 126179, 75832
                                                  //| Output exceeds cutoff limit.
  val last = x.last                               //> last  : Int = 183867
}