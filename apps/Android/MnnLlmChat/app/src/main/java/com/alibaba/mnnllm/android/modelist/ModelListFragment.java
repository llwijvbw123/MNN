// Created by ruoyi.sjd on 2025/1/13.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.

package com.alibaba.mnnllm.android.modelist;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alibaba.mls.api.HfRepoItem;
import com.alibaba.mnnllm.android.MainActivity;
import com.alibaba.mnnllm.android.R;
import com.alibaba.mnnllm.android.mainsettings.MainSettingsActivity;
import com.alibaba.mnnllm.android.utils.PreferenceUtils;
import com.alibaba.mnnllm.android.utils.RouterUtils;
import com.blankj.utilcode.util.EncryptUtils;
import com.blankj.utilcode.util.GsonUtils;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static android.app.Activity.RESULT_OK;

public class ModelListFragment extends Fragment implements ModelListContract.View {
    private RecyclerView modelListRecyclerView;

    private ModelListAdapter modelListAdapter;
    private ModelListPresenter modelListPresenter;
    private final List<HfRepoItem> hfRepoItemList = new ArrayList<>();

    private View modelListLoadingView;
    private View modelListErrorView;

    private TextView modelListErrorText;

    private boolean filterDownloaded = false;
    private String filterQuery = "";

    private void setupSearchView(Menu menu) {
        android.view.MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView != null) {
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    filterQuery = query;
                    modelListAdapter.setFilter(query, filterDownloaded);
                    return false;
                }

                @Override
                public boolean onQueryTextChange(String query) {
                    filterQuery = query;
                    modelListAdapter.setFilter(query, filterDownloaded);
                    return true;
                }
            });
            searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionExpand(MenuItem item) {
                    // SearchView is expanded
                    Log.d("SearchView", "SearchView expanded");
                    return true;
                }

                @Override
                public boolean onMenuItemActionCollapse(MenuItem item) {
                    // SearchView is collapsed
                    Log.d("SearchView", "SearchView collapsed");
                    modelListAdapter.unfilter();;
                    return true;
                }
            });
        }
    }

    private final MenuProvider menuProvider = new MenuProvider() {
        @Override
        public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
            // Inflate your menu resource here
            menuInflater.inflate(R.menu.menu_main, menu);
            setupSearchView(menu);
            MenuItem issueMenu = menu.findItem(R.id.action_github_issue);
            issueMenu.setOnMenuItemClickListener(item -> {
                if (getActivity() != null) {
                    ((MainActivity) getActivity()).onReportIssue(null);
                }
                return true;
            });

            MenuItem filterDownloadedMenu = menu.findItem(R.id.action_filter_downloaded);
            filterDownloadedMenu.setChecked(PreferenceUtils.isFilterDownloaded(getContext()));
            filterDownloadedMenu.setOnMenuItemClickListener(item -> {
                filterDownloaded = PreferenceUtils.isFilterDownloaded(getContext());
                filterDownloaded = !filterDownloaded;
                PreferenceUtils.setFilterDownloaded(getContext(), filterDownloaded);
                filterDownloadedMenu.setChecked(filterDownloaded);
                modelListAdapter.setFilter(filterQuery, filterDownloaded);
                return true;
            });
            MenuItem settingsMenu = menu.findItem(R.id.action_settings);
            settingsMenu.setOnMenuItemClickListener(item -> {
                if (getActivity() != null) {
                    RouterUtils.INSTANCE.startActivity(getActivity(), MainSettingsActivity.class);
                }
                return true;
            });

            MenuItem starGithub = menu.findItem(R.id.action_star_project);
            starGithub.setOnMenuItemClickListener(item -> {
                if (getActivity() != null) {
                    ((MainActivity) getActivity()).onStarProject(null);
                }
                return true;
            });

            MenuItem loadLocalMenu = menu.findItem(R.id.action_loadlocal);
            loadLocalMenu.setOnMenuItemClickListener(item -> {
                if (getActivity() != null) {

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Android 11 及以上版本使用 SAF
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        startActivityForResult(intent, 1001);
                    } else {
                        // 老版本请求权限
                        if (ContextCompat.checkSelfPermission(getActivity(), android.Manifest.permission.READ_EXTERNAL_STORAGE)
                                != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(getActivity(),
                                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                                    1001);
                        } else {
//                            copyFolderFromOldSDCard();
                        }
                    }
                }
                return true;
            });
        }

        @Override
        public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
            return true;
        }

        @Override
        public void onPrepareMenu(@NonNull Menu menu) {
            MenuProvider.super.onPrepareMenu(menu);
            MenuItem menuResumeAllDownlods = menu.findItem(R.id.action_resume_all_downloads);
            menuResumeAllDownlods.setVisible(modelListPresenter.getUnfisnishedDownloadsSize() > 0);
            menuResumeAllDownlods.setOnMenuItemClickListener((item)->{
                modelListPresenter.resumeAllDownloads();
                return true;
            });
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_modellist, container, false);
        modelListRecyclerView = view.findViewById(R.id.model_list_recycler_view);
        modelListLoadingView = view.findViewById(R.id.model_list_loading_view);
        modelListErrorView = view.findViewById(R.id.model_list_failed_view);
        modelListErrorText = modelListErrorView.findViewById(R.id.tv_error_text);
        modelListErrorView.setOnClickListener(v -> {
            modelListPresenter.load();
        });
        modelListRecyclerView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));

        modelListAdapter = new ModelListAdapter(hfRepoItemList);

        modelListRecyclerView.setAdapter(modelListAdapter);
        modelListPresenter = new ModelListPresenter(getContext(), this);
        modelListAdapter.setModelListListener(modelListPresenter);
        filterDownloaded = PreferenceUtils.isFilterDownloaded(getContext());
        modelListAdapter.setFilter(filterQuery, filterDownloaded);
        modelListPresenter.onCreate();
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MenuHost menuHost = requireActivity();
        menuHost.addMenuProvider(menuProvider, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        modelListPresenter.onDestroy();
    }

    @Override
    public void onListAvailable() {
        modelListErrorView.setVisibility(View.GONE);
        modelListLoadingView.setVisibility(View.GONE);
        modelListRecyclerView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onLoading() {
        if (modelListAdapter.getItemCount() > 0) {
            return;
        }
        modelListErrorView.setVisibility(View.GONE);
        modelListLoadingView.setVisibility(View.VISIBLE);
        modelListRecyclerView.setVisibility(View.GONE);
    }

    @Override
    public void onListLoadError(String error) {
        if (modelListAdapter.getItemCount() > 0) {
            return;
        }
        modelListErrorText.setText(getString(R.string.loading_failed_click_tor_retry, error));
        modelListErrorView.setVisibility(View.VISIBLE);
        modelListLoadingView.setVisibility(View.GONE);
        modelListRecyclerView.setVisibility(View.GONE);
    }

    @Override
    public ModelListAdapter getAdapter() {
        return modelListAdapter;
    }

    @Override
    public void runModel(String absolutePath, String modelId) {
        ((MainActivity) getActivity()).runModel(absolutePath, modelId, null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                copyFolderFromOldSDCard();
            } else {
//                Toast.makeText(this, "权限被拒绝，无法复制文件夹", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            if (data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    // 持久化访问权限
                    getActivity().getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    // 开始复制文件夹
                    copyFolderFromExternal(treeUri);
                }
            }
        }
    }

    private void copyFolderFromExternal(Uri sourceTreeUri) {
        ContentResolver contentResolver = getActivity().getContentResolver();

        String sha256 = checkFileExistsSha256(contentResolver, sourceTreeUri, "llm.mnn");
        if(sha256 == null){
            return;
        }
        File destinationDir = new File(getActivity().getExternalFilesDir(null) + "/"+sha256);
        if(!destinationDir.exists()){
            destinationDir.mkdirs();
        }else{
            Toast.makeText(getContext(), "模型已导入,将进行覆盖!", Toast.LENGTH_SHORT).show();
        }
        if (destinationDir != null) {
            try {
                copyDirectory(contentResolver, sourceTreeUri, destinationDir);

                SharedPreferences sharedPreferences = getActivity().getSharedPreferences("LOCAL_IMPORT" , Context.MODE_PRIVATE);
                String listStr = sharedPreferences.getString("local_import","[]");
                List<String> list = GsonUtils.fromJson(listStr, new TypeToken<List<String>>(){}.getType());
                SharedPreferences.Editor editor = sharedPreferences.edit();
                if(!list.contains(sha256)) {
                    list.add(sha256);
                }
                editor.putString("local_import",GsonUtils.toJson(list));
                editor.apply();
                Toast.makeText(getContext(), "模型导入完成", Toast.LENGTH_SHORT).show();
                modelListPresenter.load();
            } catch (IOException e) {
                destinationDir.delete();
                e.printStackTrace();
                Toast.makeText(getContext(), "模型导入失败", Toast.LENGTH_SHORT).show();
            }
        }
    }
    private String checkFileExistsSha256(ContentResolver contentResolver, Uri sourceUri, String targetFileName) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(sourceUri,
                DocumentsContract.getTreeDocumentId(sourceUri));
        try (Cursor cursor = contentResolver.query(childrenUri, null, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    @SuppressLint("Range") String displayName = cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                    @SuppressLint("Range") String documentId = cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                    if (targetFileName.equals(displayName)) {
                        try {
                            Uri childUri = DocumentsContract.buildDocumentUriUsingTree(sourceUri, documentId);
                            InputStream inputStream = contentResolver.openInputStream(childUri);
                            byte[] read = new byte[1024*1024];
                            if (inputStream != null) {
                                inputStream.read(read, 0, 1024 * 1024);
                                return EncryptUtils.encryptSHA256ToString(read);
                            }
                        }catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return null;
                    }
                }
            }
        }
        return null;
    }
    private void copyDirectory(ContentResolver contentResolver, Uri sourceUri, File destinationDir) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(sourceUri,
                DocumentsContract.getTreeDocumentId(sourceUri));
        try (Cursor cursor = contentResolver.query(childrenUri, null, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    @SuppressLint("Range") String documentId = cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                    @SuppressLint("Range") String mimeType = cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE));
                    @SuppressLint("Range") String displayName = cursor.getString(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(sourceUri, documentId);

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        // 子文件夹
                        File newDir = new File(destinationDir, displayName);
                        if (!newDir.exists()) {
                            newDir.mkdirs();
                        }
                        copyDirectory(contentResolver, childUri, newDir);
                    } else {
                        // 文件
                        File newFile = new File(destinationDir, displayName);
                        try (InputStream inputStream = contentResolver.openInputStream(childUri);
                             OutputStream outputStream = new FileOutputStream(newFile)) {
                            if (inputStream != null) {
                                copyStream(inputStream, outputStream);
                            }
                        }
                    }
                }
            }
        }
    }

    private void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    }

}
