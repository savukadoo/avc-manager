import Foundation
import AVFoundation
import CoreVideo
let url=URL(fileURLWithPath:CommandLine.arguments[1]);try? FileManager.default.removeItem(at:url)
let writer=try AVAssetWriter(outputURL:url,fileType:.mp4)
let input=AVAssetWriterInput(mediaType:.video,outputSettings:[AVVideoCodecKey:AVVideoCodecType.h264,AVVideoWidthKey:640,AVVideoHeightKey:360])
let adapter=AVAssetWriterInputPixelBufferAdaptor(assetWriterInput:input,sourcePixelBufferAttributes:[kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32ARGB,kCVPixelBufferWidthKey as String:640,kCVPixelBufferHeightKey as String:360])
writer.add(input);writer.startWriting();writer.startSession(atSourceTime:.zero)
for frame in 0..<450 {
 while !input.isReadyForMoreMediaData {Thread.sleep(forTimeInterval:0.01)}
 var pixel:CVPixelBuffer?;CVPixelBufferPoolCreatePixelBuffer(nil,adapter.pixelBufferPool!,&pixel)
 CVPixelBufferLockBaseAddress(pixel!,[])
 let bytes=CVPixelBufferGetBaseAddress(pixel!)!.assumingMemoryBound(to:UInt8.self), stride=CVPixelBufferGetBytesPerRow(pixel!)
 for y in 0..<360 {for x in 0..<640 {let p=y*stride+x*4;let moving=(x+frame*4)%640<120;bytes[p]=255;bytes[p+1]=moving ? 129:20;bytes[p+2]=moving ? 228:UInt8(40+y/4);bytes[p+3]=moving ? 202:80}}
 CVPixelBufferUnlockBaseAddress(pixel!,[])
 if !adapter.append(pixel!,withPresentationTime:CMTime(value:Int64(frame),timescale:15)){fatalError(writer.error!.localizedDescription)}
}
input.markAsFinished();let done=DispatchSemaphore(value:0);writer.finishWriting{done.signal()};done.wait();if writer.status != .completed {fatalError(writer.error!.localizedDescription)};print(url.path)
