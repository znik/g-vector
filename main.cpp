#include <stdlib.h>
#include <memory.h>

#include <cstdio>
#include <cassert>
#include <string>

#include <fstream>
#include <sstream>

#include "Fusion.h"

#include "mat.h"
#include "vec.h"


#define GRAVITY_EARTH	9.80665f

struct Vec3 {
	enum { dim = 3 };
	Vec3() { memset(_c, 0x0, sizeof(_c)); }
	Vec3(float x, float y, float z) { _c[0] = x; _c[1] = y; _c[2] = z; }
	float& operator[](int idx) { assert(idx < dim && 0 <= idx && "Idx error"); return _c[idx]; }
	float operator[](int idx) const { assert(idx < dim && 0 <= idx && "Idx error"); return _c[idx]; }
	float _c[dim];
};

#define VEC3_ZERO	Vec3()

using namespace android;

namespace {

	struct SensorFusion {

		int64_t _gyroTimestamp;
		float _gyroRateEstimation;

		// gyro rate estimation -- 200 Hz
		SensorFusion() : _gyroTimestamp(0), _gyroRateEstimation(200) {
			_fusion.init();
		}

		void readSensor(const std::string& sensorFile) {

		}

		void readSensors(const std::string& accFile, const std::string& gyroFile, const std::string& magFile) {
			// Expected format is {float, float, float, int64_t}
			// i.e. three projections to the axes and a timestamp

			enum { ACC_FILE, GYR_FILE, MAG_FILE };
			std::pair<short, std::string> files[] = {
				std::make_pair(ACC_FILE, accFile),
				std::make_pair(GYR_FILE, gyroFile),
				std::make_pair(MAG_FILE, magFile)
			};

			for (auto f : files) {
				std::ifstream fstream;
				fstream.open(f.second);
	
				if (!fstream.is_open()) {
					assert(false && "No input file...");
					return;
				}

				fstream.seekg(0, fstream.end);
				std::streamoff end = fstream.tellg();
				fstream.seekg(0, fstream.beg);

				std::string line;
				float x, y, z;
				int64_t ts;
				
				// skip the header
				std::getline(fstream, line);
				while (std::getline(fstream, line)) {
					if (line.empty()) continue;
					
					std::getline(fstream, line, ',');
					std::stringstream ss1(line);
					ss1 >> x;
					std::getline(fstream, line, ',');	
					std::stringstream ss2(line);
					ss2 >> y;
					std::getline(fstream, line, ',');	
					std::stringstream ss3(line);
					ss3 >> z;
					std::getline(fstream, line, ',');	
					std::stringstream ss4(line);
					ss4 >> ts;
	
					Vec3 plain_v(x, y, z);
					vec3_t v(&plain_v[0]);

					switch(f.first) {
					case ACC_FILE:
						_fusion.handleAcc(v);
						break;
					case GYR_FILE:
						if (_gyroTimestamp != 0) {
							const float dT = (ts - _gyroTimestamp) / 1000000000.0f;
							_fusion.handleGyro(v, dT);

							// here we estimate the gyro rate (useful for debugging)
							const float freq = 1 / dT;
							if (freq >= 100 && freq < 1000) {		// filter values obviously wrong
								const float alpha = 1 / (1 + dT);	// 1s time-constant
								_gyroRateEstimation = freq + (_gyroRateEstimation - freq) * alpha;
							}
						}
						_gyroTimestamp = ts;
						break;
					case MAG_FILE:
						_fusion.handleMag(v);
						break;
					}
				}
				fstream.close();
			}
		}

		Vec3 getGravityEstimation() {
			vec3_t g;
			if (!hasEstimate())
				return VEC3_ZERO;

			const mat33_t R(getRotationMatrix());
			g = R[2] * GRAVITY_EARTH;
			return Vec3(g.x, g.y, g.z);
		}

		bool hasEstimate() const { return _fusion.hasEstimate(); }
		mat33_t getRotationMatrix() const { return _fusion.getRotationMatrix(); }

		Fusion _fusion;
	};

	Vec3 excludeGVector(const Vec3& acceleration, const Vec3& gravityVec) {
		Vec3 linAcc = VEC3_ZERO;
		for (int i = 0; i < Vec3::dim; ++i) {
			linAcc[i] = acceleration[i] - gravityVec[i];
		}
		return linAcc;
	}
}


int main(int /*argc*/, char** /*argv*/) {

	Vec3 acc;
	Vec3 gyr;
	Vec3 mag;

	SensorFusion fusion;
	fusion.readSensors("..\\..\\data\\ACC_2014_09_17_14_24_49.csv",
		"..\\..\\data\\GYR_2014_09_17_14_24_49.csv",
		"..\\..\\data\\MAG_2014_09_17_14_24_49.csv");

//	fusion.readSensors("..\\..\\data\\ACC_2014_09_17_14_25_22.csv",
//		"..\\..\\data\\GYR_2014_09_17_14_25_22.csv",
//		"..\\..\\data\\MAG_2014_09_17_14_25_22.csv");

	Vec3 gravity = fusion.getGravityEstimation();
	Vec3 linAcc = excludeGVector(acc, gravity);

	return 0;
}
