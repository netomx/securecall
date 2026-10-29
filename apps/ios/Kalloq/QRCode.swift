import CoreImage.CIFilterBuiltins
import SwiftUI

/// QR codes are generated with CoreImage; reading them with the camera comes with the in-person
/// verification screen (step 5 of `docs/IOS_ARRANQUE.md`).
enum QRCode {
    static func image(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
