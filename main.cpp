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
#include "windows.h"
#include "shlwapi.h"
#include <tchar.h>


#define GRAVITY_EARTH	9.80665f

struct Vec3 {
	enum { dim = 3 };
	Vec3() { memset(_c, 0x0, sizeof(_c)); }
	Vec3(float x, float y, float z) { _c[0] = x; _c[1] = y; _c[2] = z; }
	float& operator[](int idx) { assert(idx < dim && 0 <= idx && "Idx error"); return _c[idx]; }
	float operator[](int idx) const { assert(idx < dim && 0 <= idx && "Idx error"); return _c[idx]; }
private:
	float _c[dim];
	friend bool operator== (const Vec3&, const Vec3&);
};

bool operator==(const Vec3& a, const Vec3& b) {
	return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
}

#define VEC3_ZERO	Vec3()

using namespace android;

namespace {
	struct SensorFusion {

		int64_t		_gyroTimestamp;
		float		_gyroRateEstimation;

		std::auto_ptr<Vec3>	_g_estimation;

		// gyro rate estimation -- 200 Hz
		SensorFusion() : _gyroTimestamp(0), _gyroRateEstimation(200) {
			_fusion.reset(new Fusion());
			_fusion->init();
		}

		bool toLinearAcceleration(const std::string& orig) {
			size_t lastSlash = orig.find_last_of('\\');
			std::string fixed;
			static const char *const prefix = "\\linearized_";
			if (std::string::npos == lastSlash)
				fixed = prefix + orig;
			else
				fixed = orig.substr(0, lastSlash) + prefix + 
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
			int index = 0;
			while (std::getline(fstream, line, ',')) {

				for (int i = 0; i < 83; ++i) { std::getline(fstream, line, ','); } // to get to l.thigh group of cols

				std::stringstream ss4(line);
				ss4 >> ts;

				for (int i = 0; i < 15; ++i) { std::getline(fstream, line, ','); } // to skip 'raw' values

				enum { ACC_DATA, GYR_DATA, MAG_DATA };
				short dtypes[] = { ACC_DATA, GYR_DATA, MAG_DATA };

				bool toWrite = hasEstimate() || 0 != _g_estimation.get();

				// start with zero timestamp
				if (toWrite && 0 == base_ts)
					base_ts = ts;

				if (toWrite)
					wstream << ts - base_ts << ',';

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

					if (((index++) % (3*65)) == 0 && hasEstimate()) {
						const Vec3& estimation = getGravityEstimation();
						_g_estimation.reset(new Vec3(estimation));
						_fusion.reset(new Fusion());
						_gyroTimestamp = 0;
					}

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
						_fusion->handleAcc(v);
						break;
					case GYR_DATA:
						if (_gyroTimestamp != 0) {
							const float dT = (ts - _gyroTimestamp) / 1000000000.0f;
							_fusion->handleGyro(v, dT);

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
						_fusion->handleMag(v);
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

		Vec3 getGravityEstimation() const  {
			vec3_t g;
			if (!hasEstimate())
				return VEC3_ZERO;

			const mat33_t R(getRotationMatrix());
			g = R[2] * GRAVITY_EARTH;
			return Vec3(g.x, g.y, g.z);
		}

		Vec3 excludeGVector (const Vec3& acceleration) const {
			Vec3 linAcc = VEC3_ZERO;
			if (!hasEstimate() && 0 == _g_estimation.get()) {
				assert(false && "Called before having an actual estimation");
				return linAcc;
			}
			Vec3 g;
			if (hasEstimate())
				g = getGravityEstimation();
			else
				g = *_g_estimation;
			for (int i = 0; i < Vec3::dim; ++i) {
				linAcc[i] = acceleration[i] - g[i];
			}
			return linAcc;
		}

		inline bool hasEstimate() const { return _fusion->hasEstimate(); }
		inline mat33_t getRotationMatrix() const { return _fusion->getRotationMatrix(); }

		std::auto_ptr<Fusion> _fusion;
	};
};

void FindFilesRecursively(LPCSTR lpFolder, LPCSTR lpFilePattern)
{
    CHAR szFullPattern[MAX_PATH];
    WIN32_FIND_DATA FindFileData;
    HANDLE hFindFile;
    
	// first we are going to process any subdirectories
    PathCombineA(szFullPattern, lpFolder, "*");
    hFindFile = FindFirstFileA(szFullPattern, &FindFileData);
    if(hFindFile != INVALID_HANDLE_VALUE)
    {
        do
        {
            if(FindFileData.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)
            {
                // found a subdirectory; recurse into it
				if (FindFileData.cFileName[0] == '.')
					continue;
                PathCombineA(szFullPattern, lpFolder, FindFileData.cFileName);
                FindFilesRecursively(szFullPattern, lpFilePattern);
            }
        } while(FindNextFileA(hFindFile, &FindFileData));
        FindClose(hFindFile);
    }
    // now we are going to look for the matching files
    PathCombineA(szFullPattern, lpFolder, lpFilePattern);
    hFindFile = FindFirstFileA(szFullPattern, &FindFileData);
    if(hFindFile != INVALID_HANDLE_VALUE)
    {
        do
        {
            if(!(FindFileData.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY))
            {
                // found a file; do something with it
                PathCombineA(szFullPattern, lpFolder, FindFileData.cFileName);
                printf_s("%s\n", szFullPattern);
				SensorFusion fusion;
				fusion.toLinearAcceleration(szFullPattern);
            }
        } while(FindNextFileA(hFindFile, &FindFileData));
        FindClose(hFindFile);
    }
}


int main(int /*argc*/, char** /*argv*/) {

#if 0
	FindFilesRecursively(
		"C:\\Users\\evgeny\\Google Drive\\phd\\mobile research\\fall_detection\\falldetection_gless_4g_320_counts", "*.csv");
#else
	SensorFusion fusion;
	fusion.toLinearAcceleration("..\\..\\data\\20110507-122357-JXL_ITDS_trial1.csv");
#endif

//	fusion.readSensors("..\\..\\data\\ACC_2014_09_17_14_24_49.csv",
//		"..\\..\\data\\GYR_2014_09_17_14_24_49.csv",
//		"..\\..\\data\\MAG_2014_09_17_14_24_49.csv");

//	fusion.readSensors("..\\..\\data\\ACC_2014_09_17_14_25_22.csv",
//		"..\\..\\data\\GYR_2014_09_17_14_25_22.csv",
//		"..\\..\\data\\MAG_2014_09_17_14_25_22.csv");

	return 0;
}
