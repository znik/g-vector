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

		bool toLinearAcceleration(const std::string& orig) {
			size_t lastSlash = orig.find_last_of('\\');
			std::string fixed;
			static const char *const prefix = "linearized_";
			if (std::string::npos == lastSlash)
				fixed = prefix + orig;
			else
				fixed = orig.substr(0, lastSlash) + "\\linearized_" + 
				orig.substr(lastSlash + 1);

			std::ifstream fstream;
			fstream.open(orig);
			if (!fstream.is_open()) {
				assert(false && "No input file...");
				return false;
			}
#pragma warning(suppress:4996)
			FILE *tmp = fopen(fixed.c_str(), "w");
			if (nullptr == tmp) {
				assert(false && "Cannot create output file...");
				fstream.close();
				return false;
			}
			fclose(tmp);

			std::ofstream wstream;
			wstream.open(fixed, std::ofstream::out | std::ofstream::trunc);
			if (!wstream.is_open()) {
				assert(false && "Cannot open output file...");
				fstream.close();
				return false;
			}

			fstream.seekg(0, fstream.beg);
			wstream.seekp(0, wstream.beg);

			std::string line;
			std::getline(fstream, line); // header
			static const char header[] = "Timestamp, LinAccX, LinAccY, LinAccZ, GyroX, GyroY, GyroZ\n";
			wstream.write(header, strlen(header));

			std::stringstream ss;
			float x, y, z;
			int64_t ts;
			int64_t base_ts = 0;
			while (std::getline(fstream, line, ',')) {

				for (int i = 0; i < 83; ++i) { std::getline(fstream, line, ','); } // to get to l.thigh group of cols

				std::stringstream ss4(line);
				ss4 >> ts;

				for (int i = 0; i < 15; ++i) { std::getline(fstream, line, ','); } // to skip 'raw' values

				enum { ACC_DATA, GYR_DATA, MAG_DATA };
				short dtypes[] = { ACC_DATA, GYR_DATA, MAG_DATA };

				bool toWrite = hasEstimate();

				// start with zero timestamp
				if (toWrite && 0 == base_ts)
					base_ts = ts;
				ts -= base_ts;
				if (toWrite)
					wstream << ts << ',';

				for (short dtype : dtypes) {

					std::getline(fstream, line, ',');
					std::stringstream ss1(line);
					ss1 >> x;
					std::getline(fstream, line, ',');
					std::stringstream ss2(line);
					ss2 >> y;
					std::getline(fstream, line, ',');
					std::stringstream ss3(line);
					ss3 >> z;

					Vec3 plain_v(x, y, z);
					const vec3_t v(&plain_v[0]);

					if (toWrite && MAG_DATA != dtype) {
						if (ACC_DATA == dtype)
							plain_v = excludeGVector(plain_v);
						wstream << plain_v[0] << ',' << plain_v[1] << ',' << plain_v[2];
						if (ACC_DATA == dtype)
							wstream << ',';
					}

					switch(dtype) {
					case ACC_DATA:
						_fusion.handleAcc(v);
						break;
					case GYR_DATA:
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
					case MAG_DATA:
						_fusion.handleMag(v);
						break;
					}
				}
				std::getline(fstream, line);
				if (toWrite)
					wstream.put('\n');
			}
			wstream.close();
			fstream.close();
			return true;
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

		Vec3 excludeGVector(const Vec3& acceleration) {
			Vec3 linAcc = VEC3_ZERO;
			if (!hasEstimate())
				return linAcc;
			for (int i = 0; i < Vec3::dim; ++i) {
				linAcc[i] = acceleration[i] - getGravityEstimation()[i];
			}
			return linAcc;
		}

		bool hasEstimate() const { return _fusion.hasEstimate(); }
		mat33_t getRotationMatrix() const { return _fusion.getRotationMatrix(); }

		Fusion _fusion;
	};
}


int main(int /*argc*/, char** /*argv*/) {

	SensorFusion fusion;
	fusion.toLinearAcceleration("..\\..\\data\\20110507-131228-JXL_SQ_trial2.csv");

//	fusion.readSensors("..\\..\\data\\ACC_2014_09_17_14_24_49.csv",
//		"..\\..\\data\\GYR_2014_09_17_14_24_49.csv",
//		"..\\..\\data\\MAG_2014_09_17_14_24_49.csv");

//	fusion.readSensors("..\\..\\data\\ACC_2014_09_17_14_25_22.csv",
//		"..\\..\\data\\GYR_2014_09_17_14_25_22.csv",
//		"..\\..\\data\\MAG_2014_09_17_14_25_22.csv");

//	Vec3 gravity = fusion.getGravityEstimation();

	return 0;
}
