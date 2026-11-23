#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/// One successful native MSI read.
@interface MsiDecodeResult : NSObject

/// Full codeword, checksum digits included.
@property (nonatomic, copy) NSString *digits;
/// Checksum scheme that validated the native read (native name, e.g. "mod1010").
@property (nonatomic, copy) NSString *nativePolicy;
@property (nonatomic, assign) NSInteger votes;
/// 0 upright, 180 upside-down, 90/270 sideways, relative to the upright view.
@property (nonatomic, assign) NSInteger relativeRotation;
/// Bounding box in the sensor buffer coordinates passed to decodePlane.
@property (nonatomic, assign) CGRect box;
/// Size of that sensor buffer.
@property (nonatomic, assign) CGSize frameSize;

@end

@interface MsiBridge : NSObject

/// Compacts a camera Y plane, contrast-stretches it, and decodes MSI Plessey.
///
/// The portable C++ core does the orientation work: `rotationDegrees` is the
/// clockwise rotation that makes the buffer upright, and the returned box is
/// mapped back into this input buffer's coordinates for the preview overlay.
+ (nullable MsiDecodeResult *)decodePlane:(const uint8_t *)plane
                                    width:(int32_t)width
                                   height:(int32_t)height
                                rowStride:(int32_t)rowStride
                          rotationDegrees:(int32_t)rotationDegrees
                           requiredPolicy:(int32_t)requiredPolicy
                                minDigits:(int32_t)minDigits
                                maxDigits:(int32_t)maxDigits
                          maxOrientations:(int32_t)maxOrientations;

@end

NS_ASSUME_NONNULL_END
