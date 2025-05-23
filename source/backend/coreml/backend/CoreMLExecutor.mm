//
//  CoreMLExecutor.mm
//  MNN
//
//  Created by MNN on 2021/03/31.
//  Copyright © 2018, Alibaba Group Holding Limited
//

#include "CoreMLDefine.h"
#import "CoreMLExecutor.h"

#include <fstream>
#include <iostream>

bool isAvailable() {
#if !defined(__APPLE__)
    return false;
#endif
#if (TARGET_OS_IPHONE)
    if (@available(iOS 11, *)) {
        return true;
    } else {
        return false;
    }
#else
    return true;
#endif
}

namespace {
NSURL* createTemporaryFile() {
    NSURL* temporaryDirectoryURL = [NSURL fileURLWithPath:NSTemporaryDirectory() isDirectory:YES];
    NSString* temporaryFilename = [[NSProcessInfo processInfo] globallyUniqueString];
    NSURL* temporaryFileURL = [temporaryDirectoryURL URLByAppendingPathComponent:temporaryFilename];
    return temporaryFileURL;
}
}  // namespace

@interface MultiArrayFeatureProvider : NSObject <MLFeatureProvider> {
    NSMutableDictionary* _inputs;
    NSSet* _featureNames;
}

- (instancetype)initWithInputs:(const std::vector<std::pair<const MNN::Tensor*, std::string>>*)inputs useImage:(bool)useImage
                 coreMlVersion:(int)coreMlVersion;
- (MLFeatureValue*)featureValueForName:(NSString*)featureName API_AVAILABLE(ios(11));
- (NSSet<NSString*>*)featureNames;

@property(nonatomic, readonly) int coreMlVersion;

@end

@implementation MultiArrayFeatureProvider

- (instancetype)initWithInputs:(const std::vector<std::pair<const MNN::Tensor*, std::string>>*)inputs useImage:(bool)useImage
                  coreMlVersion:(int)coreMlVersion {
    self = [super init];
    _inputs = [NSMutableDictionary dictionaryWithCapacity:inputs->size()];
    _coreMlVersion = coreMlVersion;
    _featureNames = nil;
    NSMutableArray* names = [[NSMutableArray alloc] init];
    for (auto& input : *inputs) {
        MLFeatureValue* value = nil;
        auto tensor = input.first;
        NSError* error = nil;
        NSString* name = [NSString stringWithCString:input.second.c_str() encoding:[NSString defaultCStringEncoding]];
        if (useImage) {
            CVPixelBufferRef pixelBuffer = NULL;
            OSType pixelFormat = kCVPixelFormatType_OneComponent8;
            size_t bytePerRow = tensor->width();
            CVReturn status = CVPixelBufferCreateWithBytes(nil, tensor->width(), tensor->height(), pixelFormat,
                                                           tensor->host<void>(), bytePerRow, nil, nil, nil, &pixelBuffer);
            if (status != kCVReturnSuccess) {
                NSLog(@"Failed to create CVPixelBufferRef for feature %@", name);
                return nil;
            }
            value = [MLFeatureValue featureValueWithPixelBuffer:pixelBuffer];
        } else {
            auto input_shape = input.first->shape();
            NSMutableArray* shape = [NSMutableArray arrayWithCapacity:input_shape.size()];
            NSMutableArray* strides = [NSMutableArray arrayWithCapacity:input_shape.size()];
            std::vector<int> stridesDim(input_shape.size());
            int curStride = 1;
            if (input_shape.size() >= 1) {
                for (int i=input_shape.size()-1; i>=0; --i) {
                    stridesDim[i] = curStride;
                    curStride *= input_shape[i];
                }
            }
            for (int i=0; i<input_shape.size(); ++i) {
                [shape addObject:@(input_shape[i])];
                [strides addObject:@(stridesDim[i])];
            }
            MLMultiArray* mlArray = [[MLMultiArray alloc] initWithDataPointer:tensor->host<float>()
                                                                        shape:shape
                                                                     dataType:MLMultiArrayDataTypeFloat32
                                                                      strides:strides
                                                                  deallocator:(^(void* bytes){})error:&error];
            if (error != nil) {
                NSLog(@"Failed to create MLMultiArray for feature %@ error: %@", name, [error localizedDescription]);
                return nil;
            }
            value= [MLFeatureValue featureValueWithMultiArray:mlArray];
        }
        [names addObject:name];
        [_inputs setValue:value forKey:(name)];
    }
    _featureNames = [NSSet setWithArray:names];
    return self;
}

- (NSSet<NSString*>*)featureNames {
    return _featureNames;
}

- (MLFeatureValue*)featureValueForName:(NSString*)featureName {
    return _inputs[featureName];
}
@end

@implementation CoreMLExecutor
- (bool)invokeWithInputs:(const std::vector<std::pair<const MNN::Tensor*, std::string>>&)inputs
                 outputs:(const std::vector<std::pair<const MNN::Tensor*, std::string>>&)outputs {
    if (_model == nil) {
        return NO;
    }

    @autoreleasepool{
        _outputArray = nil;
        _outputArray = [NSMutableArray arrayWithCapacity:0];
        NSError* error = nil;
        bool useImage = _precision == 2;
        MultiArrayFeatureProvider* inputFeature = [[MultiArrayFeatureProvider alloc] initWithInputs:&inputs useImage:useImage coreMlVersion:[self coreMlVersion]];
        if (inputFeature == nil) {
            NSLog(@"inputFeature is not initialized.");
            return NO;
        }
        MLPredictionOptions* options = [[MLPredictionOptions alloc] init];
        // options.usesCPUOnly = true;
        auto _outputFeature = [_model predictionFromFeatures:inputFeature
                                                options:options
                                                  error:&error];
        if (error != nil) {
            NSLog(@"Error executing model: %@", [error localizedDescription]);
            return NO;
        }
        NSSet<NSString*>* outputFeatureNames = [_outputFeature featureNames];
        for (auto& output : outputs) {
            NSString* outputName = [NSString stringWithCString:output.second.c_str()
                                                      encoding:[NSString defaultCStringEncoding]];
            MLFeatureValue* outputValue = [_outputFeature featureValueForName:[outputFeatureNames member:outputName]];
            if ([outputValue type] == MLFeatureTypeImage) {
                auto data = [outputValue imageBufferValue];
                CVPixelBufferLockBaseAddress(data, kCVPixelBufferLock_ReadOnly);
                auto pixelbuffer = (unsigned char*)CVPixelBufferGetBaseAddress(data);
                auto width = CVPixelBufferGetWidth(data);
                auto byte_per_row = CVPixelBufferGetBytesPerRow(data);
                for (int row = 0; row < CVPixelBufferGetHeight(data); row++) {
                    memcpy(const_cast<MNN::Tensor*>(output.first)->buffer().host + row * width, pixelbuffer + row * byte_per_row, width);
                }
                CVPixelBufferUnlockBaseAddress(data, kCVPixelBufferLock_ReadOnly);
            } else {
                auto* data = [outputValue multiArrayValue];
                if (data.dataPointer == nullptr) {
                    return NO;
                }
                [_outputArray addObject:data];
                const_cast<MNN::Tensor*>(output.first)->buffer().host = (unsigned char*)data.dataPointer;
           }
        }
        inputFeature = nil;
    }
    return YES;
}

- (bool)cleanup {
    NSError* error = nil;
    [[NSFileManager defaultManager] removeItemAtPath:_mlModelFilePath error:&error];
    if (error != nil) {
        NSLog(@"Failed cleaning up model: %@", [error localizedDescription]);
        return NO;
    }
    [[NSFileManager defaultManager] removeItemAtPath:_compiledModelFilePath error:&error];
    if (error != nil) {
        NSLog(@"Failed cleaning up compiled model: %@", [error localizedDescription]);
        return NO;
    }
    return YES;
}

- (NSURL*)saveModel:(CoreML__Specification__Model*)model {
    NSURL* modelUrl = createTemporaryFile();
    NSString* modelPath = [modelUrl path];
    if (model->specificationversion == 3) {
        _coreMlVersion = 2;
    } else if (model->specificationversion == 4) {
        _coreMlVersion = 3;
    } else {
        NSLog(@"Only Core ML models with specification version 3 or 4 are supported");
        return nil;
    }
    size_t modelSize = core_ml__specification__model__get_packed_size(model);
    std::unique_ptr<uint8_t> writeBuffer(new uint8_t[modelSize]);
    core_ml__specification__model__pack(model, writeBuffer.get());
    // TODO: Can we mmap this instead of actual writing it to phone ?
    std::ofstream file_stream([modelPath UTF8String], std::ios::out | std::ios::binary);
    const char* ptr = reinterpret_cast<const char*>(writeBuffer.get());
    file_stream.write(ptr, modelSize);
    return modelUrl;
}

- (bool)build:(NSURL*)modelUrl {
    NSError* error = nil;
    NSURL* compileUrl = [MLModel compileModelAtURL:modelUrl error:&error];
    if (error != nil) {
        NSLog(@"Error compiling model %@", [error localizedDescription]);
        return NO;
    }
    _mlModelFilePath = [modelUrl path];
    _compiledModelFilePath = [compileUrl path];

    if (@available(iOS 12.0, *)) {
        MLModelConfiguration* config = [MLModelConfiguration alloc];
        config.computeUnits = MLComputeUnitsAll;
        _model = [MLModel modelWithContentsOfURL:compileUrl configuration:config error:&error];
    } else {
        _model = [MLModel modelWithContentsOfURL:compileUrl error:&error];
    }
    if (error != NULL) {
        NSLog(@"Error Creating MLModel %@", [error localizedDescription]);
        return NO;
    }
    return YES;
}
@end

@implementation RasterLayer
- (instancetype)initWithParameterDictionary:(NSDictionary<NSString *,id> *)parameters
                                      error:(NSError * _Nullable *)error {
    self = [super init];
    return self;
}
- (void) setRegionSampler
{
    samplers.resize(regions.size());
    for (int r = 0; r < regions.size(); r++) {
        const Region& region = regions[r];
        SamplerInfo& sampler = samplers[r];
        int sizeTotal = 1;
        for (int i=0; i<3; ++i) {
            sampler.size[i] = region.size[i];
            sampler.stride[i] = region.src.stride[i];
            sampler.extent[i] = region.dst.stride[i];
            sizeTotal *= region.size[i];
        }
        sampler.size[3] = sizeTotal;
        sampler.stride[3] = region.src.offset;
        sampler.extent[3] = region.dst.offset;
    }
}

- (std::pair<MTLSize, MTLSize>)computeBestGroupAndLocal:(SamplerInfo&) s {
    MTLSize t = MTLSizeMake(s.size[0], s.size[1], s.size[2]);
    auto local = [self computeBestGroup:t];
    #define UP_DIV(x, y) (((x) + (y) - (1)) / (y))
    auto globalSize = MTLSizeMake(UP_DIV(t.width, local.width), UP_DIV(t.height, local.height), UP_DIV(t.depth, local.depth));
    #undef UP_DIV
    return std::make_pair(globalSize, local);
}

- (MTLSize)computeBestGroup:(MTLSize)t {
    if (pipeline.maxTotalThreadsPerThreadgroup > 64) {
        auto res = MTLSizeMake(8, 8, 8);
        int reduceNumber = 0;
        if (t.depth < 4) {
            res.depth = 1;
            reduceNumber++;
        }
        if (t.width < 4) {
            res.width = 1;
            reduceNumber++;
        }
        if (t.height < 4) {
            res.height = 1;
            reduceNumber++;
        }
        if (reduceNumber == 0) {
            return MTLSizeMake(4, 4, 4);
        }
        if (reduceNumber == 2) {
            if (res.width > 1) {
                res.width = 64;
            }
            if (res.height > 1) {
                res.height = 64;
            }
            if (res.depth > 1) {
                res.depth = 64;
            }
        }
        return res;
    }
    auto smallest_log2 = [](NSUInteger integer) -> NSUInteger {
        if (integer == 0)
            return 0;
        NSUInteger power = 0;
        while ((integer & 0b1) == 0) {
            integer = integer >> 1;
            power++;
        }
        return power;
    };
    auto pwarp = smallest_log2(pipeline.threadExecutionWidth);
    auto px = smallest_log2(t.width), sx = (NSUInteger)ceil(log2(t.width));
    auto py = smallest_log2(t.height), sy = (NSUInteger)ceil(log2(t.height));

    // accurately match on x
    if (px >= pwarp) {
        return {pipeline.threadExecutionWidth, 1, 1};
    }
    // accurately match on xy
    else if (px + py >= pwarp && sx < pwarp / 2) {
        NSUInteger x = pow(2, px);
        return {x, pipeline.threadExecutionWidth / x, 1};
    }
    // similarly match on x
    else if (sx >= pwarp) {
        return {pipeline.threadExecutionWidth, 1, 1};
    }
    // similarly match on xy
    else if (sx + sy >= pwarp) {
        NSUInteger x = pow(2, sx);
        return {x, pipeline.threadExecutionWidth / x, 1};
    }

    // on xyz (for most shaders do not protect gid.z, z axis must be accurately match)
    auto pz = smallest_log2(t.depth);
    auto sz = pz;
    if (px + py + pz >= pwarp) {
        NSUInteger x = pow(2, px), y = pow(2, py);
        return {x, y, pipeline.threadExecutionWidth / x / y};
    } else if (sx + sy + sz >= pwarp) {
        NSUInteger x = pow(2, sx), z = pow(2, MIN(sz, pwarp - sx));
        return {x, pipeline.threadExecutionWidth / x / z, z};
    } else {
        NSUInteger z = pow(2, sz);
        return {t.width, t.height, z};
    }
}

- (BOOL)setWeightData:(NSArray<NSData *> *)weights
                error:(NSError * _Nullable *)error {
    assert(weights.count > 1);
    outputShape.resize(weights[0].length / sizeof(int));
    memcpy(outputShape.data(), [weights[0] bytes], weights[0].length);
    regions.resize(weights.count - 1);
    for (int i = 1; i < weights.count; i++) {
        auto regionPtr = [weights[i] bytes];
        memcpy(&regions[i-1], regionPtr, weights[i].length);
    }
    [self setRegionSampler];
    return YES;
}

- (NSArray<NSArray<NSNumber *> *> *)outputShapesForInputShapes:(NSArray<NSArray<NSNumber *> *> *)inputShapes
                                                         error:(NSError * _Nullable *)error {
    NSMutableArray* shape = [[NSMutableArray alloc] initWithCapacity: outputShape.size()];
    for (int x : outputShape) {
        [shape addObject: [NSNumber numberWithInt:x]];
    }
    NSArray* outputShapes = @[ shape ];
    return outputShapes;
}

// execute on cpu
- (BOOL)evaluateOnCPUWithInputs:(NSArray<MLMultiArray *> *)inputs
                        outputs:(NSArray<MLMultiArray *> *)outputs
                          error:(NSError * _Nullable *)error {
    // NSLog(@"%@ -> %@", inputs[0].shape, outputs[0].shape);
    assert(inputs.count == regions.size());
    float* outputPtr = static_cast<float*>(outputs[0].dataPointer);
    for (int i = 0; i < inputs.count; i++) {
        const float* inputPtr = static_cast<const float*>(inputs[i].dataPointer);
        const auto& region = regions[i];
        for (int z = 0; z < region.size[0]; z++) {
            for (int y = 0; y < region.size[1]; y++) {
                for (int x = 0; x < region.size[2]; x++) {
                    outputPtr[region.dst.offset + z * region.dst.stride[0] + y * region.dst.stride[1] + x * region.dst.stride[2]] =
                        inputPtr[region.src.offset + z * region.src.stride[0] + y * region.src.stride[1] + x * region.src.stride[2]];
                }
            }
        }
    }
    return YES;
}

@end

@implementation DumpLayer
- (instancetype)initWithParameterDictionary:(NSDictionary<NSString *,id> *)parameters
                                      error:(NSError * _Nullable *)error {
    self = [super init];
    return self;
}

- (BOOL)setWeightData:(NSArray<NSData *> *)weights
                error:(NSError * _Nullable *)error {
    return YES;
}

- (NSArray<NSArray<NSNumber *> *> *)outputShapesForInputShapes:(NSArray<NSArray<NSNumber *> *> *)inputShapes
                                                         error:(NSError * _Nullable *)error {
    for (int i = 0; i < inputShapes.count; i++) {
        printf("### shape_%d : { ", i);
        for (int j = 0; j < inputShapes[i].count; j++) {
            printf("%d, ", inputShapes[i][j].intValue);
        }
        printf(" }\n");
    }
    return inputShapes;
}

- (BOOL)evaluateOnCPUWithInputs:(NSArray<MLMultiArray *> *)inputs
                        outputs:(NSArray<MLMultiArray *> *)outputs
                          error:(NSError * _Nullable *)error {
    assert(inputs.count == 1 && outputs.count == 1);
    assert(inputs[0].count == outputs[0].count);
    const float* inputPtr = static_cast<float*>(inputs[0].dataPointer);
    float* outputPtr = static_cast<float*>(outputs[0].dataPointer);
    printf(">>> "); for (int i = 0; i < 10; i++) printf("%f, ", inputPtr[i]); printf("\n");
    // memcpy(outputPtr, inputPtr, outputs[0].count);
    memcpy(outputPtr, inputPtr, outputs[0].count * sizeof(float));
    return YES;
}
@end
